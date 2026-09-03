"""Descubrimiento y selección del catálogo de entrada de Winstall."""

from __future__ import annotations

import asyncio
from typing import Any

from sqlalchemy import select

from app.core.config import Settings
from app.core.logging import get_logger
from app.core.url_protector import UrlProtector
from app.db.enums import (
    ResolutionStatus,
    ScrapeOutcome,
    ScrapeScope,
)
from app.db.models import SoftwareApp
from app.repositories.catalog import CatalogRepository
from app.repositories.logs import ResolverLogRepository
from app.repositories.pipeline import (
    QUEUE_SEARCHER_FILTER,
    PipelineRepository,
)
from app.repositories.runs import ScrapeRunRepository, worker_id
from app.scraper.installer_policy import version_label_is_preferred
from app.scraper.pipeline_runtime import (
    PipelineRuntime,
    async_session_local,
    retry_database_pool_operation,
)
from app.scraper.pipeline_support import (
    provider_snapshot_absence_outcome,
    set_current,
)
from app.scraper.winstall import (
    WinstallApp,
    WinstallClient,
    WinstallDetailIncompleteError,
    parse_winstall_app,
    winstall_detail_fingerprint,
    winstall_summary_fingerprint,
)

logger = get_logger(__name__)
"""Estado global asociado a `logger`.
"""


class SearcherWorker:
    """Ejecuta el procesamiento en segundo plano de `Searcher`."""

    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `SearcherWorker`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.worker_id = f"searcher:{worker_id()}"
        """Estado de instancia asociado a `worker_id`.
        """

    async def run(self, runtime: PipelineRuntime) -> None:
        """Ejecuta `run` dentro de `SearcherWorker`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.
        """
        try:
            async with WinstallClient(self.settings) as winstall:
                if runtime.scope == ScrapeScope.SELECTED:
                    await set_current(
                        self.settings,
                        runtime.run_id,
                        None,
                        None,
                        "searcher_loading_selected_apps",
                    )
                    (
                        targets,
                        manifest_app_ids,
                        manifest_winstall_ids,
                        provider_missing,
                        skipped_unchanged,
                    ) = await retry_database_pool_operation(
                        self.settings,
                        "searcher_select_scope",
                        lambda: self._select_local_targets(runtime),
                    )
                else:
                    await set_current(
                        self.settings,
                        runtime.run_id,
                        None,
                        None,
                        "searcher_stabilizing_winstall_catalog",
                    )
                    catalog_snapshot = await winstall.catalog_snapshot()
                    (
                        targets,
                        manifest_app_ids,
                        manifest_winstall_ids,
                        provider_missing,
                        skipped_unchanged,
                    ) = await retry_database_pool_operation(
                        self.settings,
                        "searcher_select_scope",
                        lambda: self._select_scope_targets(runtime, catalog_snapshot),
                    )
                await self._save_manifest(
                    runtime,
                    manifest_app_ids,
                    manifest_winstall_ids,
                )
                if skipped_unchanged:
                    await runtime.increment("apps_skipped", skipped_unchanged)
                    await runtime.increment("apps_skipped_unchanged", skipped_unchanged)
                for package_id in provider_missing:
                    await self._record_provider_absence(runtime, package_id)

                for lightweight_app in targets:
                    if not await runtime.before_next_item():
                        break
                    if (
                        self.settings.scrape_max_apps > 0
                        and runtime.counters.apps_discovered >= self.settings.scrape_max_apps
                    ):
                        break
                    if not await self._wait_for_backpressure(runtime):
                        break
                    await set_current(
                        self.settings,
                        runtime.run_id,
                        lightweight_app.package_id,
                        lightweight_app.name,
                        "searcher_fetching_winstall_app",
                    )
                    try:
                        app = await winstall.get_app(lightweight_app.package_id)
                    except WinstallDetailIncompleteError:
                        if lightweight_app.installer_data_complete:
                            app = lightweight_app
                            logger.warning(
                                "winstall_cached_detail_used",
                                winstall_id=lightweight_app.package_id,
                                scope=runtime.scope.value,
                                reason="detail_incomplete",
                            )
                        else:
                            await self._record_provider_failure(
                                runtime,
                                lightweight_app.package_id,
                                "detail_incomplete",
                            )
                            continue
                    except Exception as exc:
                        if lightweight_app.installer_data_complete:
                            app = lightweight_app
                            logger.warning(
                                "winstall_cached_detail_used",
                                winstall_id=lightweight_app.package_id,
                                scope=runtime.scope.value,
                                reason=exc.__class__.__name__,
                            )
                        else:
                            await self._record_provider_failure(
                                runtime,
                                lightweight_app.package_id,
                                exc.__class__.__name__,
                            )
                            continue

                    download_versions: dict[str, str | None] = {}
                    for version in app.versions:
                        for url in version.installers:
                            current_version = download_versions.get(url)
                            if url not in download_versions or version_label_is_preferred(
                                current_version,
                                version.version,
                                app.latest_version,
                            ):
                                download_versions[url] = version.version

                    payload: dict[str, Any] = {
                        "package_id": app.package_id,
                        "winstall_url": (
                            f"{self.settings.winstall_base_url}/apps/{app.package_id}"
                        ),
                        "official_url": app.homepage,
                        "source_code_url": None,
                        "winstall_download_urls": app.installer_urls,
                        "winstall_downloads": [
                            {
                                "url": url,
                                "label": None,
                                "context": version,
                            }
                            for url, version in download_versions.items()
                        ],
                        "provider_detail_complete": app.installer_data_complete,
                        "winstall_summary_fingerprint": winstall_summary_fingerprint(app),
                        "winstall_detail_fingerprint": winstall_detail_fingerprint(app),
                        "scope": runtime.scope.value,
                        "force_refresh": True,
                        "app": app.raw,
                    }
                    depth = await self._enqueue_app(runtime, app, payload)
                    logger.info(
                        "searcher_item_enqueued",
                        queue=QUEUE_SEARCHER_FILTER,
                        winstall_id=app.package_id,
                        scope=runtime.scope.value,
                        depth=depth,
                    )
                    await runtime.increment("apps_discovered")
        finally:
            runtime.searcher_done.set()

    async def _select_local_targets(
        self,
        runtime: PipelineRuntime,
    ) -> tuple[list[WinstallApp], list[str], list[str], list[str], int]:
        """Resuelve un scope seleccionado sin descargar el catálogo remoto completo."""
        async with async_session_local()() as session:
            catalog = CatalogRepository(
                session,
                UrlProtector(self.settings.url_protection_secret),
            )
            local_targets = await catalog.snapshot_refresh_targets(
                app_ids=list(runtime.selected_app_ids)
            )
        found = {app.id for app in local_targets}
        if found != set(runtime.selected_app_ids):
            raise ValueError("selected_scope_contains_unknown_app_id")
        targets = [self._cached_winstall_app(app) for app in local_targets]
        return (
            targets,
            [str(app.id) for app in local_targets],
            [app.winstall_id for app in local_targets],
            [],
            0,
        )

    @staticmethod
    def _cached_winstall_app(app: SoftwareApp) -> WinstallApp:
        """Reconstruye el último detalle completo sin inventar campos ausentes."""
        metadata = app.metadata_json if isinstance(app.metadata_json, dict) else {}
        if metadata:
            try:
                cached = parse_winstall_app(metadata)
            except (TypeError, ValueError):
                cached = None
            if cached is not None and cached.package_id == app.winstall_id:
                return cached
        return WinstallApp(
            package_id=app.winstall_id,
            name=app.name,
            description=None,
            publisher=None,
            homepage=None,
            icon=None,
            icon_url=None,
            latest_version=None,
            tags=[],
            versions=[],
            raw={},
        )

    async def _select_scope_targets(
        self,
        runtime: PipelineRuntime,
        remote_apps: list[WinstallApp],
    ) -> tuple[list[WinstallApp], list[str], list[str], list[str], int]:
        """Materializa un manifest local/remote antes de solicitar ningún detalle."""
        remote_by_id = {app.package_id: app for app in remote_apps}
        async with async_session_local()() as session:
            catalog = CatalogRepository(
                session,
                UrlProtector(self.settings.url_protection_secret),
            )
            if runtime.scope == ScrapeScope.UNRESOLVED:
                local_targets = await catalog.snapshot_refresh_targets(
                    statuses={"review", "missing"}
                )
            elif runtime.scope == ScrapeScope.SELECTED:
                local_targets = await catalog.snapshot_refresh_targets(
                    app_ids=list(runtime.selected_app_ids)
                )
                found = {app.id for app in local_targets}
                if found != set(runtime.selected_app_ids):
                    raise ValueError("selected_scope_contains_unknown_app_id")
            else:
                local_targets = []

            if runtime.scope in {ScrapeScope.UNRESOLVED, ScrapeScope.SELECTED}:
                winstall_ids = [app.winstall_id for app in local_targets]
                scoped_targets = [
                    remote_by_id[value] for value in winstall_ids if value in remote_by_id
                ]
                missing = [value for value in winstall_ids if value not in remote_by_id]
                return (
                    scoped_targets,
                    [str(app.id) for app in local_targets],
                    winstall_ids,
                    missing,
                    0,
                )

            states = await catalog.winstall_refresh_states()

        if runtime.scope == ScrapeScope.FULL:
            return (
                remote_apps,
                [str(states[app.package_id][0]) for app in remote_apps if app.package_id in states],
                [app.package_id for app in remote_apps],
                [],
                0,
            )

        targets: list[WinstallApp] = []
        unchanged = 0
        manifest_app_ids: list[str] = []
        for app in remote_apps:
            state = states.get(app.package_id)
            fingerprint = winstall_summary_fingerprint(app)
            if state is None or state[1] != "available" or state[2] != fingerprint:
                targets.append(app)
                if state is not None:
                    manifest_app_ids.append(str(state[0]))
            else:
                unchanged += 1
        return (
            targets,
            manifest_app_ids,
            [app.package_id for app in targets],
            [],
            unchanged,
        )

    async def _save_manifest(
        self,
        runtime: PipelineRuntime,
        app_ids: list[str],
        winstall_ids: list[str],
    ) -> None:
        async def persist() -> None:
            async with async_session_local()() as session:
                runs = ScrapeRunRepository(session, self.settings)
                await runs.set_manifest(
                    runtime.run_id,
                    app_ids=app_ids,
                    winstall_ids=winstall_ids,
                )
                await session.commit()

        await retry_database_pool_operation(
            self.settings,
            "searcher_save_manifest",
            persist,
        )

    async def _enqueue_app(
        self,
        runtime: PipelineRuntime,
        app: WinstallApp,
        payload: dict[str, Any],
    ) -> int:
        """Persiste un elemento sin convertir la contención del pool en fallo del run."""

        async def persist() -> int:
            async with async_session_local()() as session:
                pipeline = PipelineRepository(session)
                await pipeline.enqueue(
                    QUEUE_SEARCHER_FILTER,
                    app.package_id,
                    app.name,
                    payload,
                    runtime.run_id,
                    force=True,
                )
                depth = await pipeline.queue_depth(
                    QUEUE_SEARCHER_FILTER,
                    run_id=runtime.run_id,
                )
                await pipeline.save_snapshot(
                    run_id=runtime.run_id,
                    worker_id=self.worker_id,
                    stage="searcher",
                    package_id=app.package_id,
                    app_name=app.name,
                    url=payload["winstall_url"],
                    html=None,
                )
                await session.commit()
                return depth

        return await retry_database_pool_operation(
            self.settings,
            "searcher_enqueue",
            persist,
        )

    async def _record_provider_failure(
        self,
        runtime: PipelineRuntime,
        package_id: str,
        reason: str,
    ) -> None:
        await runtime.increment("apps_failed")
        await runtime.increment("apps_transient_failed")
        logger.warning(
            "winstall_provider_incomplete",
            winstall_id=package_id,
            scope=runtime.scope.value,
            reason=reason,
        )

    async def _record_provider_absence(
        self,
        runtime: PipelineRuntime,
        package_id: str,
    ) -> None:
        """Clasifica una ausencia en el snapshot estable sin inventar un fallo transitorio."""
        has_verification = False
        async with async_session_local()() as session:
            catalog = CatalogRepository(
                session,
                UrlProtector(self.settings.url_protection_secret),
            )
            logs = ResolverLogRepository(session)
            software_app = await session.scalar(
                select(SoftwareApp).where(SoftwareApp.winstall_id == package_id).limit(1)
            )
            if software_app is not None:
                has_verification = (
                    await catalog.active_absence_verification(software_app.id)
                ) is not None
                if not has_verification:
                    source = await catalog.default_source_for_app(software_app.id)
                    if source is not None:
                        await catalog.mark_source_status(
                            source.id,
                            ResolutionStatus.REQUIRES_MANUAL_REVIEW,
                        )
            outcome = provider_snapshot_absence_outcome(has_verification)
            await logs.add(
                phase="provider",
                status=outcome.value,
                message="The package is absent from the stable Winstall snapshot.",
                safe_metadata={"winstall_id": package_id},
            )
            await session.commit()

        if outcome == ScrapeOutcome.CONFIRMED_MISSING:
            await runtime.increment("apps_confirmed_missing")
        else:
            await runtime.increment("apps_needs_review")
        logger.info(
            "winstall_package_absent",
            winstall_id=package_id,
            scope=runtime.scope.value,
            outcome=outcome.value,
        )

    async def _wait_for_backpressure(self, runtime: PipelineRuntime) -> bool:
        """Ejecuta el paso interno `_wait_for_backpressure`.

        Args:
            runtime (PipelineRuntime): Valor de `runtime` utilizado por la operación.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        limit = self.settings.scrape_searcher_backpressure_limit
        if limit <= 0:
            return True
        while not runtime.stop_event.is_set():
            if not await runtime.before_next_item():
                return False

            async def read_depth() -> int:
                async with async_session_local()() as session:
                    return await PipelineRepository(session).queue_depth(
                        QUEUE_SEARCHER_FILTER,
                        run_id=runtime.run_id,
                    )

            depth = await retry_database_pool_operation(
                self.settings,
                "searcher_backpressure",
                read_depth,
            )
            if depth < limit:
                return True
            await set_current(
                self.settings,
                runtime.run_id,
                None,
                None,
                "searcher_waiting_for_filter_backpressure",
            )
            logger.info(
                "searcher_backpressure_wait",
                queue=QUEUE_SEARCHER_FILTER,
                depth=depth,
                limit=limit,
            )
            await asyncio.sleep(self.settings.scrape_searcher_backpressure_sleep_seconds)
        return False
