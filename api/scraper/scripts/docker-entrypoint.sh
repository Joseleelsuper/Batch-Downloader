#!/usr/bin/env sh
set -eu

python - <<'PY'
import asyncio
from app.core.free_threading import assert_free_threaded_runtime
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy import text
from app.core.config import get_settings

async def main():
    assert_free_threaded_runtime()
    engine = create_async_engine(get_settings().database_url, pool_pre_ping=True)
    last_error = None
    for _ in range(60):
        try:
            async with engine.connect() as connection:
                await connection.execute(text("SELECT 1"))
            await engine.dispose()
            return
        except Exception as exc:
            last_error = exc
            await asyncio.sleep(2)
    await engine.dispose()
    raise SystemExit(f"Database is not ready: {last_error}")

asyncio.run(main())
PY

alembic upgrade head

case "${1:-api}" in
  api)
    exec uvicorn app.main:app --host 0.0.0.0 --port 8000
    ;;
  scheduler)
    exec python -m app.worker scheduler
    ;;
  scrape-once)
    exec python -m app.worker scrape-once
    ;;
  *)
    exec "$@"
    ;;
esac
