import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { createServer } from 'vite';

const app = {
  id: 'app-1', slug: 'example-app', packageId: 'Example.App', name: 'Aplicación de ejemplo',
  publisher: 'Example', tags: [], operatingSystems: ['windows', 'linux'], sourceLabel: 'Oficial',
  resolutionStatus: 'direct', validationStatus: 'valid', downloadable: true,
  updatedAt: '2026-10-01T00:00:00Z',
};
const bundle = {
  id: 'bundle-1', slug: 'example-bundle', name: 'Bundle de ejemplo', description: 'Aplicaciones de ejemplo',
  creatorUsername: 'person', type: 'official', visibility: 'public', starCount: 0,
  appCount: 1, tags: [], operatingSystems: ['windows', 'linux'], previewApps: [app], apps: [app],
  platformAvailability: [
    { operatingSystem: 'windows', downloadableAppCount: 1, previewApps: [app] },
    { operatingSystem: 'linux', downloadableAppCount: 1, previewApps: [app] },
  ],
  updatedAt: '2026-10-01T00:00:00Z',
};
const user = {
  id: 'user-1', username: 'person', email: 'person@example.com', emailVerified: true,
  role: 'USER', createdAt: '2026-10-01T00:00:00Z',
};
const job = {
  id: 'job-1', status: 'QUEUED', failureCode: null, progress: 0,
  requestedCount: 1, acceptedCount: 1, omittedCount: 0, items: [],
  createdAt: '2026-10-01T00:00:00Z', expiresAt: '2026-10-02T00:00:00Z',
};

async function tabTo(page, target) {
  for (let index = 0; index < 80; index += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`Keyboard could not reach ${target.toString()}`);
}

async function mockApi(page, state) {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    let body;
    let status = 200;
    if (path === '/api/v1/auth/me') body = state.authenticated ? user : null;
    else if (path === '/api/v1/auth/csrf') body = { token: 'keyboard-test' };
    else if (path === '/api/v1/auth/magic-link/request') status = 204;
    else if (path.startsWith('/api/v1/locales/')) status = 404;
    else if (path === '/api/v1/apps/stats') body = {
      total: 1, filters: { all: 1, available: 1, review: 0, missing: 0 },
      generatedAt: '2026-10-01T00:00:00Z',
    };
    else if (path === '/api/v1/apps/facets') body = { tags: [], publishers: [] };
    else if (path === '/api/v1/apps/app-1') body = { ...app, notes: '', downloadOptions: [] };
    else if (path === '/api/v1/apps') body = { data: [app], page: 1, pageSize: 12, total: 1 };
    else if (path === '/api/v1/bundles/bundle-1') body = bundle;
    else if (path === '/api/v1/bundles') body = {
      data: url.searchParams.get('type') === 'official' ? [bundle] : [],
      page: 1, pageSize: 3, total: url.searchParams.get('type') === 'official' ? 1 : 0,
    };
    else if (path === '/api/v1/download-jobs/linux-preview') body = {
      target: 'apt', architecture: 'x86_64', totalCount: 1,
      automaticCount: 1, manualCount: 0, omittedCount: 0, items: [],
    };
    else if (path === '/api/v1/download-jobs' && request.method() === 'POST') {
      state.createdJobs += 1;
      body = job;
    }
    else if (path === '/api/v1/download-jobs/job-1') body = job;
    else if (path === '/api/v1/download-jobs/job-1/activity') status = 204;
    else if (path === '/api/v1/download-jobs/job-1/events') {
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
      return;
    } else if (path === '/api/v1/users/me/bundles/custom-tags') body = [];
    else if (path === '/api/v1/users/me/bundles' && request.method() === 'POST') body = {
      id: 'own-bundle-1', slug: 'my-bundle', name: 'My bundle', description: '',
      visibility: 'private', appCount: 1, tags: [], apps: [app], version: 0,
      updatedAt: '2026-10-01T00:00:00Z',
    };
    else if (path === '/api/v1/users/me/bundles') body = { data: [], page: 1, pageSize: 20, total: 0 };
    else if (path === '/api/v1/users/me/bundles/own-bundle-1') body = {
      id: 'own-bundle-1', slug: 'my-bundle', name: 'My bundle', description: '',
      visibility: 'private', appCount: 1, tags: [], apps: [app], version: 0,
      updatedAt: '2026-10-01T00:00:00Z',
    };
    else if (path === '/api/v1/seo/metadata') status = 500;
    else status = 404;
    await route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(body ?? {}) });
  });
}

async function runViewport(browser, baseUrl, viewport) {
  const barriers = [];
  const context = await browser.newContext({ locale: 'es-ES', viewport });
  await context.addInitScript(() => {
    Object.defineProperty(window, 'showSaveFilePicker', { configurable: true, value: undefined });
  });
  const page = await context.newPage();
  page.setDefaultTimeout(10_000);
  await page.routeWebSocket('**/api/v1/catalog/ws', () => {});
  const state = { authenticated: false, createdJobs: 0 };
  await mockApi(page, state);
  try {
    await page.goto(baseUrl);
    const catalogLink = page.getByRole('link', { name: 'Abrir catálogo' });
    await tabTo(page, catalogLink);
    assert.notEqual(await catalogLink.evaluate((element) => getComputedStyle(element).outlineStyle), 'none');
    await page.keyboard.press('Enter');
    await page.waitForURL('**/catalog');
    process.stdout.write(`Catalog opened at ${viewport.width}px.\n`);

    if (viewport.width < 700) {
      const filters = page.getByRole('button', { name: 'Filtros', exact: true });
      await tabTo(page, filters);
      await page.keyboard.press('Enter');
      await page.getByRole('dialog', { name: 'Filtros' }).waitFor();
      const all = page.getByRole('dialog', { name: 'Filtros' }).getByRole('button', { name: /Todas/ });
      await tabTo(page, all);
      await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelector('.filter-item[aria-pressed="true"]')?.textContent?.includes('Todas'));
      await page.keyboard.press('Escape');
      assert.equal(await filters.evaluate((element) => element === document.activeElement), true);
    } else {
      const all = page.getByRole('button', { name: /Todas/ });
      await tabTo(page, all);
      await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelector('.filter-item[aria-pressed="true"]')?.textContent?.includes('Todas'));
    }

    const search = page.getByRole('textbox', { name: 'Buscar aplicaciones' });
    await tabTo(page, search);
    await page.keyboard.type('Ejemplo');
    const selection = page.getByRole('checkbox', { name: 'Seleccionar Aplicación de ejemplo' });
    await tabTo(page, selection);
    await page.keyboard.press('Space');
    assert.equal(await selection.isChecked(), true);
    const details = page.getByRole('button', { name: 'Mostrar detalles de Aplicación de ejemplo' });
    await tabTo(page, details);
    await page.keyboard.press('Enter');
    const closeDetails = page.getByRole('button', { name: 'Ocultar detalles de Aplicación de ejemplo' });
    assert.equal(await closeDetails.getAttribute('aria-expanded'), 'true');
    assert.equal(await closeDetails.evaluate((element) => element === document.activeElement), true);
    await page.keyboard.press('Enter');
    assert.equal(await details.getAttribute('aria-expanded'), 'false');
    const download = page.getByRole('button', { name: 'Descargar ZIP' });
    await tabTo(page, download);
    await page.keyboard.press('Enter');
    await page.getByRole('region', { name: 'Trabajos de descarga' }).waitFor();
    await page.getByRole('progressbar', { name: 'Preparación del ZIP' }).waitFor();
    assert.equal(state.createdJobs, 1);

    await page.goto(`${baseUrl}/bundles/bundle-1`);
    const linux = page.getByRole('button', { name: 'Linux: 1 aplicaciones disponibles' });
    await tabTo(page, linux);
    await page.keyboard.press('Enter');
    assert.equal(await linux.getAttribute('aria-pressed'), 'true');
    const bundleDownload = page.getByRole('button', { name: 'Descargar bundle (1)' });
    await tabTo(page, bundleDownload);
    await page.keyboard.press('Enter');
    await page.getByRole('dialog', { name: 'Prepara tu instalación de Linux' }).waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('dialog', { name: 'Prepara tu instalación de Linux' }).waitFor({ state: 'hidden' });
    const bundleDownloadHandle = await bundleDownload.elementHandle();
    await page.waitForFunction((element) => document.activeElement === element, bundleDownloadHandle);
    if (!await bundleDownload.evaluate((element) => element === document.activeElement)) {
      const focusedTag = await page.evaluate(() => document.activeElement?.tagName ?? 'none');
      barriers.push(`${viewport.width}px: focus moved to ${focusedTag} instead of the bundle download button after Escape`);
    }
    await page.keyboard.press('Enter');
    const linuxDialog = page.getByRole('dialog', { name: 'Prepara tu instalación de Linux' });
    await linuxDialog.waitFor();
    const cancelLinux = linuxDialog.getByRole('button', { name: 'Cancelar' });
    await tabTo(page, cancelLinux);
    await page.keyboard.press('Enter');
    await linuxDialog.waitFor({ state: 'hidden' });
    await page.waitForFunction((element) => document.activeElement === element, bundleDownloadHandle);
    assert.equal(await bundleDownload.evaluate((element) => element === document.activeElement), true);
    assert.equal(await bundleDownload.getAttribute('title'), null);
    const windows = page.getByRole('button', { name: 'Windows: 1 aplicaciones disponibles' });
    await tabTo(page, windows);
    await page.keyboard.press('Enter');
    await tabTo(page, bundleDownload);
    await page.keyboard.press('Enter');
    await page.getByRole('region', { name: 'Trabajos de descarga' }).waitFor();

    await page.goto(`${baseUrl}/login`);
    const email = page.getByRole('textbox', { name: 'Correo electrónico' });
    await tabTo(page, email);
    await page.keyboard.press('Enter');
    const emailError = page.getByRole('alert', { name: '' });
    await emailError.getByText('Introduce una dirección de correo válida.').waitFor();
    assert.equal(await email.getAttribute('aria-describedby'), await emailError.getAttribute('id'));
    assert.equal(await email.getAttribute('aria-invalid'), 'true');
    await page.keyboard.type('person@example.com');
    await emailError.waitFor({ state: 'hidden' });
    assert.equal(await email.getAttribute('aria-invalid'), null);
    await page.keyboard.press('Enter');
    await page.getByRole('status').getByText('Revisa tu bandeja de entrada').waitFor();

    state.authenticated = true;
    await page.goto(`${baseUrl}/dashboard/bundles/new`);
    const name = page.getByRole('textbox', { name: 'Nombre' });
    await tabTo(page, name);
    await page.keyboard.type('My bundle');
    const add = page.getByRole('button', { name: 'Añadir Aplicación de ejemplo' });
    await tabTo(page, add);
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: 'Quitar Aplicación de ejemplo' }).waitFor();
    const tags = page.getByRole('button', { name: 'Elegir etiquetas' });
    await tabTo(page, tags);
    await page.keyboard.press('Enter');
    const tagDialog = page.getByRole('dialog', { name: 'Etiquetas y editores' });
    await tagDialog.waitFor();
    await page.keyboard.press('Escape');
    await tagDialog.waitFor({ state: 'hidden' });
    assert.equal(await tags.evaluate((element) => element === document.activeElement), true);
    const save = page.getByRole('button', { name: 'Guardar' });
    await tabTo(page, save);
    await page.keyboard.press('Enter');
    await page.waitForURL('**/dashboard/bundles/own-bundle-1/edit');
    process.stdout.write(`Keyboard flows completed at ${viewport.width}×${viewport.height}.\n`);
    return barriers;
  } finally {
    await context.close();
  }
}

const server = await createServer({ server: { host: '127.0.0.1', port: 0 } });
let browser;
try {
  await server.listen();
  const baseUrl = server.resolvedUrls.local[0].replace(/\/$/, '');
  browser = await chromium.launch({ headless: true });
  process.stdout.write(`Chromium ${browser.version()}.\n`);
  const barriers = [
    ...await runViewport(browser, baseUrl, { width: 1280, height: 800 }),
    ...await runViewport(browser, baseUrl, { width: 390, height: 844 }),
  ];
  if (barriers.length) throw new Error(`Keyboard barriers found:\n${barriers.join('\n')}`);
} finally {
  await browser?.close();
  await server.close();
}
