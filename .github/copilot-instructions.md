# Batch Downloader AI Coding Notes

## Architecture
- Monorepo for Batch Downloader. MySQL is the catalog source of truth; pgvector is reserved for derived semantic search.
- `services/core-api` is now the public Spring Boot API for `/api/*`: catalog, downloads proxy, admin auth, bundles, requests, scraper monitor, and audit.
- `api/scrapper` is an internal FastAPI worker/API for Winstall scraping, installer resolution, validation, icon resolution, LLM descriptions, and scheduler control.
- `services/webapp/src/main/resources/frontend` is React/Vite/TypeScript served by Nginx. Nginx proxies `/api/*` to `core-api:8080`, not to `scraper-api`.
- `services/translation-service/locales` holds Spanish defaults and a translation template.

## Configuration
- Compose files and Spring `application.properties` intentionally use direct `${VAR}` placeholders only. Do not add `${VAR:-fallback}` or Spring `${VAR:fallback}` defaults.
- Add every new runtime variable to the root `.env.example`, grouped by service. Update `.env` only when needed for local Docker execution.
- Core admin login uses `CORE_API_ADMIN_USERNAME`, `CORE_API_ADMIN_PASSWORD_HASH` (BCrypt), `CORE_API_JWT_SECRET`, and an HttpOnly JWT cookie.

## Scraper Rules
- Do not add Scrapy. The scraper stack is FastAPI, HTTPX, selectolax/lxml, Playwright fallback, SQLAlchemy/Alembic, asyncmy, APScheduler, tldextract, dnspython, and structlog.
- Keep Winstall access behind `WinstallClient`: API first, `__NEXT_DATA__` second, HTML fallback third.
- Resolver order: official/GitHub-specific resolver, generic HTTP candidates, Playwright fallback, Winstall fallback, then manual review.
- `DownloadValidator` must remain independent and enforce public DNS, private/reserved IP blocking, redirect revalidation, content/extension checks, and size limits. HTTP is allowed only for verified Winstall fallback candidates.
- Never log signed URLs, cookies, auth headers, prompts/responses, or installer contents. Store resolved URLs only in `resolved_url_encrypted`.
- `SCRAPPER_SCRAPE_MAX_APPS=0` and `SCRAPPER_LLM_MAX_APPS_PER_RUN=0` both mean unlimited.
- Scheduler control is cooperative through `scraper_commands`; check commands between apps and keep `scrape_runs.current_*` fields updated.

## Data Ownership
- Alembic in `api/scrapper/alembic` owns scraper/catalog tables: `software_apps`, sources, resolved sources, tags, resolver logs, scrape runs, and scraper commands.
- Flyway in `services/core-api/src/main/resources/db/migration` owns public/admin tables: bundles, bundle items/tags/stars, software requests, and admin audit logs.
- `description` is Winstall's short text. `long_description` is AI-generated Spanish enrichment and must not overwrite `description`.

## Frontend
- Routes: `/` home with bundle sections, `/catalog`, `/bundles/:slug`, `/login`, and `/admin/*`.
- Catalog search, filters, tags, pagination, stats, and details are server-backed through Spring; do not load the whole catalog in the browser.
- Admin uses cookie auth. Do not store JWTs in `localStorage`.
- Keep the existing compact tool UI: top bar, filter rail, dense tables, teal download actions, status chips, and details drawer.

## Commands
```bash
docker compose config --quiet
docker compose up --build
```
```bash
cd services/webapp/src/main/resources/frontend
npm run build
npm test -- --run
```
```bash
docker compose run --rm -v "${PWD}/api/scrapper/tests:/app/tests" scraper-api pytest /app/tests
python -m app.worker scrape-once
python -m app.worker scheduler
```
