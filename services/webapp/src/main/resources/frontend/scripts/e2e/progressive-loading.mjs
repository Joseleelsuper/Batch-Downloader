import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { gzipSync } from 'node:zlib';
import { chromium } from 'playwright';
import { preview } from 'vite';

const artifacts = process.env.PROGRESSIVE_ARTIFACTS ?? resolve(tmpdir(), 'batch-progressive-loading');
await mkdir(artifacts, { recursive: true });
const manifest = JSON.parse(await readFile('dist/.vite/manifest.json', 'utf8'));
const loginAsset = manifest['src/pages/account/UserLoginPage.tsx'].file;
const app = {
  id: 'app-1', slug: 'example', packageId: 'Example.App', name: 'Example App', publisher: 'Example',
  tags: [], operatingSystems: ['windows'], downloadable: true, resolutionStatus: 'direct',
  validationStatus: 'valid', sourceLabel: 'Official', updatedAt: '2026-11-01T00:00:00Z',
};
const bundle = {
  id: 'bundle-1', slug: 'example', name: 'Pack disponible', description: 'Aplicaciones de ejemplo',
  creatorUsername: 'person', type: 'official', visibility: 'official', starCount: 0,
  appCount: 1, tags: [], operatingSystems: ['windows'], previewApps: [app], apps: [app],
  platformAvailability: [{ operatingSystem: 'windows', downloadableAppCount: 1, previewApps: [app] }],
  updatedAt: '2026-11-01T00:00:00Z',
};
const identity = { id: 'account-1', username: 'person', email: 'person@example.com', role: 'USER', emailVerified: true };
const run = {
  id: 'run-1', status: 'running', scope: 'incremental', currentAppName: 'Scraper independiente',
  currentPhase: 'resolving', startedAt: '2026-11-01T00:00:00Z', heartbeatAt: new Date().toISOString(),
  appsResolved: 0, appsDiscovered: 0, appsFailed: 0, appsSkipped: 0, appsNeedsReview: 0,
  appsConfirmedMissing: 0, appsTransientFailed: 0, stopRequested: false,
};
const gates = [];
function gate() {
  let release;
  const promise = new Promise((done) => { release = done; });
  gates.push(release);
  return { promise, release };
}

async function mockApi(page, state = {}) {
  state.requests = new Map();
  await page.routeWebSocket('**/api/v1/**', (socket) => socket.close());
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    state.requests.set(path, (state.requests.get(path) ?? 0) + 1);
    if (state.hold?.(url)) await state.gate.promise;
    let body = null;
    let status = 200;
    if (path === '/api/v1/auth/me') body = state.role ? { ...identity, role: state.role } : null;
    else if (path === '/api/v1/apps') body = { data: [app], total: 1, page: 1, pageSize: 12 };
    else if (path === '/api/v1/apps/stats') body = { total: 1, filters: { all: 1, available: 1, review: 0, missing: 0 } };
    else if (path === '/api/v1/apps/facets') body = { tags: [], publishers: [] };
    else if (path === '/api/v1/apps/app-1') body = { ...app, longDescription: 'Detalle de ejemplo', downloadOptions: [], notes: '' };
    else if (path === '/api/v1/bundles') body = { data: [bundle], total: 1, page: 1, pageSize: 3 };
    else if (path === '/api/v1/bundles/facets') body = { tags: [], creators: [] };
    else if (path === '/api/v1/bundles/bundle-1') body = bundle;
    else if (path === '/api/v1/admin/scraper/current') body = run;
    else if (path === '/api/v1/admin/scraper/logs' && state.failLogs) { status = 503; state.failLogs = false; }
    else if (path.startsWith('/api/v1/admin/scraper/')) body = [];
    else if (path === '/api/v1/users/me/dashboard') body = {
      account: identity, counts: { bundles: 0, publicBundles: 0, privateBundles: 0, downloads: 0 },
      recentDownloads: [], recentBundles: [],
    };
    else if (path === '/api/v1/users/me/bundles') body = { data: [], total: 0, page: 1, pageSize: 20 };
    else if (path === '/api/v1/users/me/bundles/own-1') body = { ...bundle, id: 'own-1', version: 1, visibility: 'private' };
    else if (path === '/api/v1/admin/apps') body = { data: [], total: 0, page: 1, pageSize: 12 };
    else if (path === '/api/v1/admin/audit') body = [];
    else if (path === '/api/v1/admin/semantic/overview') body = {
      service: 'semantic', status: 'ok', database: true, searchReady: false, model: null,
      index: { expected: 0, indexed: 0, complete: false }, indexer: { present: false },
    };
    else status = 404;
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  });
}

async function createPage(browser, viewport, state) {
  const context = await browser.newContext({ viewport, locale: 'es-ES' });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await mockApi(page, state);
  return page;
}

async function checkViewport(browser, base, viewport) {
  const suffix = viewport.width;
  const pending = gate();
  const home = await createPage(browser, viewport, {
    gate: pending, hold: (url) => url.pathname === '/api/v1/apps' ||
      (url.pathname === '/api/v1/bundles' && url.searchParams.get('type') === 'community'),
  });
  const requests = [];
  home.on('request', (request) => requests.push(request.url()));
  await home.goto(base);
  await home.locator('.bundle-card').first().waitFor();
  assert(await home.locator('.topbar').isVisible());
  assert.equal(await home.locator('.app-compact-card').count(), 0);
  assert(!requests.some((url) => /fonts\.(?:googleapis|gstatic)\.com|\/css\//.test(url)));
  assert.equal(await home.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await home.screenshot({ path: resolve(artifacts, `home-loading-${suffix}.png`), fullPage: true });
  pending.release();
  await home.locator('.deferred-section').last().scrollIntoViewIfNeeded();
  await home.locator('.app-compact-card').first().waitFor();
  await home.screenshot({ path: resolve(artifacts, `home-ready-${suffix}.png`), fullPage: true });
  await home.context().close();

  const chunk = gate();
  const login = await createPage(browser, viewport);
  await login.route(`**/${loginAsset}`, async (route) => { await chunk.promise; await route.continue(); });
  await login.goto(`${base}/login`);
  await login.getByRole('status').waitFor();
  assert(await login.locator('.topbar').isVisible());
  assert(await login.locator('.site-footer').isVisible());
  chunk.release();
  await login.locator('.auth-form').waitFor();
  await login.context().close();

  const logs = gate();
  const state = { role: 'ADMIN', gate: logs, hold: (url) => url.pathname.endsWith('/scraper/logs'), failLogs: true };
  const scraper = await createPage(browser, viewport, state);
  await scraper.goto(`${base}/admin/scraper`);
  await scraper.getByText('Scraper independiente', { exact: true }).waitFor();
  assert.equal(state.requests.get('/api/v1/admin/scraper/logs') ?? 0, 0);
  await scraper.locator('.deferred-section').last().scrollIntoViewIfNeeded();
  const logSection = scraper.locator('section[aria-labelledby="scraper-logs-heading"]');
  await logSection.getByRole('status').waitFor();
  const currentRequests = state.requests.get('/api/v1/admin/scraper/current');
  logs.release();
  await logSection.getByRole('alert').waitFor();
  await logSection.getByRole('button', { name: 'Reintentar' }).click();
  await logSection.locator('.empty-state').waitFor();
  assert.equal(state.requests.get('/api/v1/admin/scraper/current'), currentRequests);
  assert.equal(state.requests.get('/api/v1/admin/scraper/logs'), 2);
  await scraper.screenshot({ path: resolve(artifacts, `scraper-${suffix}.png`), fullPage: true });
  await scraper.context().close();

  const dashboardGate = gate();
  const account = await createPage(browser, viewport, {
    role: 'USER', gate: dashboardGate, hold: (url) => url.pathname.endsWith('/dashboard'),
  });
  await account.goto(`${base}/dashboard`);
  await account.locator('.account-shell').waitFor();
  assert.equal(await account.locator('.account-stat').count(), 0);
  await account.getByRole('status').waitFor();
  dashboardGate.release();
  await account.locator('.account-stat').first().waitFor();
  await account.context().close();
  console.log(`Carga progresiva, navegación y reintento local: ${suffix}px OK`);
}

async function initialMetrics(browser, server, label) {
  const page = await createPage(browser, { width: 1440, height: 900 });
  await page.route('https://fonts.googleapis.com/**', (route) => route.fulfill({ contentType: 'text/css', body: '' }));
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false, latency: 150, downloadThroughput: 250000, uploadThroughput: 125000, connectionType: 'cellular3g',
  });
  await page.goto(server);
  await page.locator('.home-hero').waitFor();
  await page.waitForFunction(() => performance.getEntriesByName('first-contentful-paint').length > 0);
  const metrics = await page.evaluate(() => ({
    fcpMs: performance.getEntriesByName('first-contentful-paint')[0]?.startTime,
    cssRequests: performance.getEntriesByType('resource').filter((item) => item.name.includes('.css')).length,
  }));
  await page.context().close();
  return { label, ...metrics };
}

async function checkRoutes(browser, base) {
  const admin = await createPage(browser, { width: 1440, height: 900 }, { role: 'ADMIN' });
  const errors = [];
  admin.on('pageerror', (error) => errors.push(error.message));
  for (const [path, selector] of [
    ['/catalog', '.catalog-panel'], ['/catalog/bundles', '.bundle-catalog-grid'],
    ['/catalog/tags', '.facet-header'], ['/catalog/bundles/creators', '.facet-header'],
    ['/bundles/bundle-1', '.bundle-detail-header'], ['/admin', '.metric-grid'],
    ['/admin/apps', '.admin-apps-master'], ['/admin/bundles', '.bundle-admin-editor'],
    ['/admin/audit', '.admin-table-card'], ['/admin/semantic', '.semantic-admin-grid'],
  ]) {
    await admin.goto(`${base}${path}`);
    await admin.locator(selector).waitFor();
  }
  assert.deepEqual(errors, []);
  await admin.context().close();
  const account = await createPage(browser, { width: 390, height: 844 }, { role: 'USER' });
  account.on('pageerror', (error) => errors.push(error.message));
  for (const path of ['/dashboard/bundles', '/profile', '/dashboard/bundles/new', '/dashboard/bundles/own-1/edit']) {
    await account.goto(`${base}${path}`);
    await account.locator('.account-page').waitFor();
    if (path.includes('/bundles/') && path !== '/dashboard/bundles') {
      await account.locator('fieldset:not([disabled])').waitFor();
    }
  }
  assert.deepEqual(errors, []);
  await account.context().close();
  console.log('Rutas públicas, cuenta y administración: OK');
}

let browser;
const servers = [];
try {
  const server = await preview({ preview: { host: '127.0.0.1', port: 0, open: false } });
  servers.push(server);
  const base = `http://127.0.0.1:${server.httpServer.address().port}`;
  browser = await chromium.launch({ headless: true });
  if (!process.argv.includes('--metrics-only')) {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) await checkViewport(browser, base, viewport);
  const failedLogin = await createPage(browser, { width: 1440, height: 900 });
  let fail = true;
  await failedLogin.route(`**/${loginAsset}`, (route) => fail ? route.abort('failed') : route.continue());
  await failedLogin.goto(`${base}/login`);
  await failedLogin.getByRole('alert').waitFor();
  assert(await failedLogin.locator('.topbar').isVisible());
  fail = false;
  await failedLogin.getByRole('button', { name: 'Recargar' }).click();
  await failedLogin.locator('.auth-form').waitFor();
  await failedLogin.context().close();
  await checkRoutes(browser, base);
  }
  const metrics = [];
  if (process.env.PROGRESSIVE_BASELINE_DIST) {
    const baseline = await preview({ build: { outDir: process.env.PROGRESSIVE_BASELINE_DIST }, preview: { host: '127.0.0.1', port: 0, open: false } });
    servers.push(baseline);
    metrics.push(await initialMetrics(browser, `http://127.0.0.1:${baseline.httpServer.address().port}`, 'before'));
  }
  metrics.push(await initialMetrics(browser, base, 'after'));
  const initial = new Set();
  function visit(key) {
    const item = manifest[key];
    if (initial.has(item.file)) return;
    initial.add(item.file);
    item.imports?.forEach(visit);
  }
  visit('index.html');
  let initialGzipBytes = 0;
  for (const file of initial) initialGzipBytes += gzipSync(await readFile(`dist/${file}`)).byteLength;
  const result = { metrics, initialGzipBytes, initialJavaScriptFiles: initial.size };
  await writeFile(resolve(artifacts, 'metrics.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
  console.log(`Capturas y métricas: ${artifacts}`);
} finally {
  gates.forEach((release) => release());
  if (browser) await browser.close();
  for (const server of servers) {
    server.httpServer.closeAllConnections();
    await new Promise((done) => server.httpServer.close(done));
  }
}
