"""Edición de recetas Linux bajo autenticación interna y control de versión."""
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import joinedload

from app.api.dependencies import require_internal_service_token
from app.db.models import (LinuxInstallProfileRow, ResolvedSource, SoftwareApp,
                           SoftwareAppDependency, SoftwareAppDependencyVersion)
from app.db.session import get_session
from app.schemas.linux_install import (DependenciesWrite, FORMAT_STRATEGIES, ProfileWrite,
                                       default_profile)

router = APIRouter(prefix="/internal/v1/linux", dependencies=[Depends(require_internal_service_token)])
Session = Annotated[AsyncSession, Depends(get_session)]

@router.get("/apps/{app_id}/sources/{source_ref}/profile")
async def get_profile(app_id: UUID, source_ref: UUID, session: Session):
    source = await session.scalar(select(ResolvedSource).options(joinedload(ResolvedSource.source)).where(ResolvedSource.id == source_ref))
    if not source or source.source.software_app_id != app_id or source.source.operating_system != "linux":
        raise HTTPException(404, detail={"code": "linux_source_not_found"})
    row = source.install_profile
    return {"version": row.version if row else 0, "status": row.status if row else "draft",
            "profile": row.profile_json if row else default_profile(source.extension)}

@router.put("/apps/{app_id}/sources/{source_ref}/profile")
async def put_profile(app_id: UUID, source_ref: UUID, body: ProfileWrite, session: Session):
    source = await session.scalar(select(ResolvedSource).options(joinedload(ResolvedSource.source))
                                  .where(ResolvedSource.id == source_ref).with_for_update())
    if not source or source.source.software_app_id != app_id or source.source.operating_system != "linux":
        raise HTTPException(404, detail={"code": "linux_source_not_found"})
    row = source.install_profile
    if (row.version if row else 0) != body.expected_version:
        raise HTTPException(409, detail={"code": "linux_profile_version_conflict"})
    if body.profile.strategy not in (FORMAT_STRATEGIES.get((source.extension or "").lower()), "manual"):
        raise HTTPException(422, detail={"code": "linux_profile_format_mismatch"})
    # Las dependencias se administran en el grafo global para impedir ciclos ocultos.
    if body.profile.dependencies:
        raise HTTPException(422, detail={"code": "use_application_dependencies"})
    if row is None:
        row = LinuxInstallProfileRow(source_ref=source_ref, version=0)
        session.add(row)
    row.version += 1
    row.status = body.status
    row.profile_json = body.profile.model_dump(mode="json", by_alias=True, exclude_none=True)
    await session.commit()
    return {"version": row.version, "status": row.status, "profile": row.profile_json}

@router.get("/apps/{app_id}/dependencies")
async def get_dependencies(app_id: UUID, session: Session):
    if not await session.get(SoftwareApp, app_id):
        raise HTTPException(404, detail={"code": "app_not_found"})
    row = await session.get(SoftwareAppDependencyVersion, app_id)
    dependencies = (await session.scalars(select(SoftwareAppDependency.dependency_app_id)
                                          .where(SoftwareAppDependency.app_id == app_id))).all()
    return {"version": row.version if row else 0, "dependencies": [str(d) for d in dependencies]}

@router.put("/apps/{app_id}/dependencies")
async def put_dependencies(app_id: UUID, body: DependenciesWrite, session: Session):
    # Un mutex de catálogo estable serializa modificaciones del grafo, incluidos ciclos cruzados.
    await session.execute(select(SoftwareApp.id).order_by(SoftwareApp.id).limit(1).with_for_update())
    app = await session.get(SoftwareApp, app_id)
    if not app: raise HTTPException(404, detail={"code": "app_not_found"})
    version = await session.get(SoftwareAppDependencyVersion, app_id)
    if (version.version if version else 0) != body.expected_version:
        raise HTTPException(409, detail={"code": "dependency_version_conflict"})
    ids = set(body.dependencies)
    found = set((await session.scalars(select(SoftwareApp.id).where(SoftwareApp.id.in_(ids)))).all())
    if ids != found or app_id in ids:
        raise HTTPException(422, detail={"code": "invalid_dependencies"})
    graph: dict[UUID, set[UUID]] = {}
    for edge in (await session.scalars(select(SoftwareAppDependency))).all():
        graph.setdefault(edge.app_id, set()).add(edge.dependency_app_id)
    graph[app_id] = ids
    pending = [(app_id, False)]
    active, done = set(), set()
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
    await session.execute(delete(SoftwareAppDependency).where(SoftwareAppDependency.app_id == app_id))
    session.add_all(SoftwareAppDependency(app_id=app_id, dependency_app_id=d) for d in ids)
    if version is None:
        version = SoftwareAppDependencyVersion(app_id=app_id, version=0)
        session.add(version)
    version.version += 1
    await session.commit()
    return {"version": version.version, "dependencies": sorted(str(d) for d in ids)}
