#!/usr/bin/env sh
set -eu

python - <<'PY'
import asyncio
import os
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy import text

async def main():
    url = os.environ.get("SCRAPPER_DATABASE_URL")
    if not url:
        return
    engine = create_async_engine(url, pool_pre_ping=True)
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
