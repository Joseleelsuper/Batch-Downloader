# Batch Downloader AI Coding Notes

## Architecture
- This repo is a monorepo for Batch Downloader, a tool for discovering known desktop apps and downloading installers.
- `api/scrapper` is the current MVP backend: a Python FastAPI service that syncs Winstall, resolves installer URLs, stores metadata in MySQL, and temporarily exposes `/api/apps`.
- The planned long-term public backend is still Spring Boot under `services/core-api`; FastAPI owning `/api/apps` is an explicit MVP decision.
- `services/webapp/src/main/resources/frontend` is the React/Vite/TypeScript frontend. It queries the API for search, filters, pagination, app details, and individual download redirects.
- `services/translation-service/locales` contains the Spanish default strings and `template.json` for future languages.

## Scraper Conventions
- Do not use Scrapy for the MVP. The scraper stack is FastAPI, HTTPX, selectolax, lxml, Playwright fallback, SQLAlchemy/Alembic, asyncmy, APScheduler, tldextract, dnspython, structlog.
- Keep Winstall behind `WinstallClient`; its internal endpoints can change. Use API first, then `__NEXT_DATA__`, then HTML fallback.
- Resolver flow: official page HTTP candidates, Playwright network/click fallback, Winstall installer fallback, then manual review.
- Every candidate must pass `DownloadValidator`: HTTPS, allowed domain, public DNS, redirect revalidation, HEAD/partial GET, content type, extension/signature, size limit.
- Never log signed URLs, cookies, auth headers, or installer contents. Store resolved URLs only in `resolved_url_encrypted`.

## Data Model
- MySQL is the source of truth. Alembic migrations live in `api/scrapper/alembic`.
- MVP tables: `software_apps`, `download_sources`, `source_allowed_domains`, `resolved_sources`, `resolver_logs`, `scrape_runs`.
- Track statuses separately: app availability, resolver result (`direct`, `fallback`, `requires_manual_review`, `missing`, `broken`), and validation result.
- Daily scraping is a separate worker/scheduler process, not part of FastAPI startup. `scrape_runs.heartbeat_at` prevents overlapping recent runs.

## Developer Commands
```bash
cd api/scrapper
pip install -r requirements.txt
playwright install chromium
alembic upgrade head
uvicorn app.main:app
python -m app.worker scrape-once
python -m app.worker scheduler
pytest
```
```bash
cd services/webapp/src/main/resources/frontend
npm install
npm run dev
npm run build
npm test
```

## Frontend Notes
- The accepted visual spec is the generated Codex concept: top bar, left filter rail, central table, teal download buttons, status chips, and right details drawer.
- Search, filters, and pagination must be server-backed through `/api/apps`; do not load the full catalog into the browser.
- Individual downloads go through `GET /api/apps/{id}/download`, which returns a `307` redirect when available.
