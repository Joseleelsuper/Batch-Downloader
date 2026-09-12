"""Comprueba reservas obsoletas, reintentos acotados y publicación atómica de inspecciones."""

import uuid
from datetime import timedelta
from unittest.mock import AsyncMock

import pytest

from app.core.time import utc_now
from app.db.models import ManualInstallerInspection, ScraperWorkItem, WebsiteAppDiscovery
from app.scraper.inspection_lifecycle import InspectionProgress, load_inspection


@pytest.mark.parametrize(
    ("model", "key", "prefix"),
    [
        (ManualInstallerInspection, "inspection_id", "inspection"),
        (WebsiteAppDiscovery, "discovery_id", "website_discovery"),
    ],
)
@pytest.mark.parametrize("case", ["invalid", "missing", "applied", "expired", "timeout", "valid"])
async def test_reserved_record_retains_each_rejection_code(model, key, prefix, case):
    """Ambos flujos conservan sus códigos y solo confirman una transición terminal al rechazar."""
    record_id = uuid.uuid4()
    item = ScraperWorkItem(payload_json={key: "invalid" if case == "invalid" else str(record_id)})
    record = model(
        status=case if case in {"applied", "expired"} else "queued",
        expires_at=utc_now() + timedelta(seconds=-1 if case == "timeout" else 60),
    )
    session, pipeline = AsyncMock(), AsyncMock()
    session.get.return_value = None if case == "missing" else record
    result = await load_inspection(session, pipeline, item, model, key, prefix)
    if case == "valid":
        assert result is record
        session.commit.assert_not_awaited()
        pipeline.discard.assert_not_awaited()
    else:
        assert result is None
        session.commit.assert_awaited_once()
        if case == "invalid":
            pipeline.fail.assert_awaited_once_with(item, f"invalid_{prefix}_id")
            session.get.assert_not_awaited()
        else:
            reason = {"missing": "not_found", "timeout": "expired"}.get(case, case)
            pipeline.discard.assert_awaited_once_with(item, f"{prefix}_{reason}")
    if case == "timeout":
        assert (record.status, record.phase, record.error_code) == (
            "expired",
            "expired",
            f"{prefix}_expired",
        )


@pytest.mark.parametrize(("attempts", "delay"), [(1, 2), (5, 32), (6, 60), (7, None)])
async def test_retry_budget_and_exponential_delay(attempts, delay):
    """El último intento falla; los anteriores conservan un único aviso y una espera acotada."""
    record = ManualInstallerInspection(status="running", warnings_json=["retry:timeout"])
    item = ScraperWorkItem(attempts=attempts)
    session, pipeline = AsyncMock(), AsyncMock()
    await InspectionProgress(session, pipeline, item, record, 7).retry("timeout")
    session.commit.assert_awaited_once()
    if delay is None:
        assert (record.status, record.error_code) == ("failed", "timeout")
        pipeline.fail.assert_awaited_once_with(item, "timeout")
        pipeline.requeue.assert_not_awaited()
    else:
        assert (record.status, record.phase, record.error_code) == ("queued", "retry_wait", None)
        assert record.warnings_json == ["retry:timeout"]
        pipeline.requeue.assert_awaited_once_with(item, "timeout", delay_seconds=delay)


async def test_result_and_queue_completion_share_the_callers_commit():
    """El resultado está preparado antes de completar la cola y confirmar la misma sesión."""
    record = WebsiteAppDiscovery(status="running", warnings_json=["original"])
    item = ScraperWorkItem(attempts=1)
    session, pipeline = AsyncMock(), AsyncMock()

    async def complete(reserved):
        assert reserved is item
        assert record.result_json == {"name": "App"}
        assert record.status == record.phase == "ready"
        session.commit.assert_not_awaited()

    pipeline.complete.side_effect = complete
    await InspectionProgress(session, pipeline, item, record, 3).complete(
        {"name": "App"}, ["original", "new", ""]
    )
    assert record.warnings_json == ["original", "new"]
    session.commit.assert_awaited_once()
