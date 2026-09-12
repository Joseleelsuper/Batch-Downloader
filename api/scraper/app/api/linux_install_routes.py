"""Administra recetas por fuente exacta y dependencias por aplicación con autenticación interna,
bloqueo y versiones optimistas.

See Also:
    app.schemas.linux_install: Valida recetas y restricciones de los cuerpos recibidos.
    app.scraper.linux_install: Completa firmas y requisitos usados por el runtime.
"""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from app.api.dependencies import require_internal_service_token
from app.db.models import (
    LinuxInstallProfileRow,
    ResolvedSource,
    SoftwareApp,
    SoftwareAppDependency,
    SoftwareAppDependencyVersion,
)
from app.db.session import get_session
from app.schemas.linux_install import (
    FORMAT_STRATEGIES,
    DependenciesWrite,
    ProfileWrite,
    default_profile,
)

router = APIRouter(
    prefix="/internal/v1/linux", dependencies=[Depends(require_internal_service_token)]
)
Session = Annotated[AsyncSession, Depends(get_session)]


@router.get("/apps/{app_id}/sources/{source_ref}/profile")
async def get_profile(app_id: UUID, source_ref: UUID, session: Session):
    """Comprueba que la resolución pertenece a la aplicación y es Linux y devuelve su receta o un
    borrador derivado del formato.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        source_ref: Referencia exacta de resolución que debe pertenecer a la aplicación cuando
            la ruta la especifica.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.

    Returns:
        versión, estado y perfil; versión cero y draft si aún no hay fila.

    Raises:
        fastapi.HTTPException: 404 con linux_source_not_found si falta la fuente, pertenece a
            otra aplicación o no es Linux.
    """
    source = await session.scalar(
        select(ResolvedSource)
        .options(joinedload(ResolvedSource.source))
        .where(ResolvedSource.id == source_ref)
    )
    if (
        not source
        or source.source.software_app_id != app_id
        or source.source.operating_system != "linux"
    ):
        raise HTTPException(404, detail={"code": "linux_source_not_found"})
    row = source.install_profile
    return {
        "version": row.version if row else 0,
        "status": row.status if row else "draft",
        "profile": row.profile_json if row else default_profile(source.extension),
    }


@router.put("/apps/{app_id}/sources/{source_ref}/profile")
async def put_profile(app_id: UUID, source_ref: UUID, body: ProfileWrite, session: Session):
    """Bloquea la resolución, exige versión coincidente y estrategia compatible y guarda la
    receta con versión incrementada.
    Las dependencias se editan en la aplicación y no se admiten dentro de este perfil.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        source_ref: Referencia exacta de resolución que debe pertenecer a la aplicación cuando
            la ruta la especifica.
        body: Datos de escritura validados, incluida la versión esperada para detectar
            conflictos.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.

    Returns:
        perfil persistido, estado y nueva versión tras commit.

    Raises:
        fastapi.HTTPException: 404 si no corresponde a una fuente Linux de la aplicación, 409
            por versión concurrente o 422 por formato o dependencias embebidas.
    """
    source = await session.scalar(
        select(ResolvedSource)
        .options(joinedload(ResolvedSource.source))
        .where(ResolvedSource.id == source_ref)
        .with_for_update()
    )
    if (
        not source
        or source.source.software_app_id != app_id
        or source.source.operating_system != "linux"
    ):
        raise HTTPException(404, detail={"code": "linux_source_not_found"})
    row = source.install_profile
    if (row.version if row else 0) != body.expected_version:
        raise HTTPException(409, detail={"code": "linux_profile_version_conflict"})
    if body.profile.strategy not in (
        FORMAT_STRATEGIES.get((source.extension or "").lower()),
        "manual",
    ):
        raise HTTPException(422, detail={"code": "linux_profile_format_mismatch"})
    # Las dependencias se administran en el grafo global para impedir ciclos ocultos.
    if body.profile.dependencies:
        raise HTTPException(422, detail={"code": "use_application_dependencies"})
    if row is None:
        row = LinuxInstallProfileRow(source_ref=source_ref, version=0)
        source.install_profile = row
        session.add(row)
    row.version += 1
    row.status = body.status
    row.profile_json = body.profile.model_dump(mode="json", by_alias=True, exclude_none=True)
    await session.commit()
    return {"version": row.version, "status": row.status, "profile": row.profile_json}


@router.get("/apps/{app_id}/dependencies")
async def get_dependencies(app_id: UUID, session: Session):
    """Comprueba la existencia de aplicación y consulta sus dependencias directas con la versión
    del conjunto.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.

    Returns:
        UUID textuales y versión; cero si el conjunto aún no tiene fila de versión.

    Raises:
        fastapi.HTTPException: 404 con app_not_found si la aplicación no existe.
    """
    if not await session.get(SoftwareApp, app_id):
        raise HTTPException(404, detail={"code": "app_not_found"})
    row = await session.get(SoftwareAppDependencyVersion, app_id)
    dependencies = (
        await session.scalars(
            select(SoftwareAppDependency.dependency_app_id).where(
                SoftwareAppDependency.app_id == app_id
            )
        )
    ).all()
    return {"version": row.version if row else 0, "dependencies": [str(d) for d in dependencies]}


@router.put("/apps/{app_id}/dependencies")
async def put_dependencies(app_id: UUID, body: DependenciesWrite, session: Session):
    # Un mutex de catálogo estable serializa modificaciones del grafo, incluidos ciclos cruzados.
    """Serializa cambios del grafo bloqueando la primera aplicación, exige versión coincidente y
    valida destinos existentes, sin autorreferencia ni ciclos.
    Reemplaza las aristas, incrementa la versión y confirma la transacción.

    Args:
        app_id: UUID de la aplicación propietaria de la inspección, receta o dependencias.
        body: Datos de escritura validados, incluida la versión esperada para detectar
            conflictos.
        session: Sesión independiente de la petición; las escrituras se confirman antes de
            responder.

    Returns:
        nueva versión y dependencias únicas ordenadas por UUID.

    Raises:
        fastapi.HTTPException: 404 si falta la aplicación, 409 por versión concurrente o 422
            por identidades inválidas o ciclo del grafo.
    """
    await session.execute(
        select(SoftwareApp.id).order_by(SoftwareApp.id).limit(1).with_for_update()
    )
    app = await session.get(SoftwareApp, app_id)
    if not app:
        raise HTTPException(404, detail={"code": "app_not_found"})
    version = await session.get(SoftwareAppDependencyVersion, app_id)
    if (version.version if version else 0) != body.expected_version:
        raise HTTPException(409, detail={"code": "dependency_version_conflict"})
    ids = set(body.dependencies)
    found = set(
        (await session.scalars(select(SoftwareApp.id).where(SoftwareApp.id.in_(ids)))).all()
    )
    if ids != found or app_id in ids:
        raise HTTPException(422, detail={"code": "invalid_dependencies"})
    graph: dict[UUID, set[UUID]] = {}
    for edge in (await session.scalars(select(SoftwareAppDependency))).all():
        graph.setdefault(edge.app_id, set()).add(edge.dependency_app_id)
    graph[app_id] = ids
    pending = [(app_id, False)]
    active: set[UUID] = set()
    done: set[UUID] = set()
    while pending:
        node, finished = pending.pop()
        if finished:
            active.remove(node)
            done.add(node)
            continue
        if node in done:
            continue
        if node in active:
            raise HTTPException(422, detail={"code": "dependency_cycle"})
        active.add(node)
        pending.append((node, True))
        pending.extend((dependency, False) for dependency in graph.get(node, set()))
    await session.execute(
        delete(SoftwareAppDependency).where(SoftwareAppDependency.app_id == app_id)
    )
    session.add_all(SoftwareAppDependency(app_id=app_id, dependency_app_id=d) for d in ids)
    if version is None:
        version = SoftwareAppDependencyVersion(app_id=app_id, version=0)
        session.add(version)
    version.version += 1
    await session.commit()
    return {"version": version.version, "dependencies": sorted(str(d) for d in ids)}
