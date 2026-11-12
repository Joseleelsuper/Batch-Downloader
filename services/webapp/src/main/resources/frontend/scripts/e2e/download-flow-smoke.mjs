import assert from 'node:assert/strict';
import { spawnSync, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)),
  '..', '..', '..', '..', '..', '..', '..', '..');
const executables = process.platform === 'win32'
  ? { docker: 'C:/Program Files/Docker/Docker/resources/bin/docker.exe',
    python: resolve(repositoryRoot, '.venv/Scripts/python.exe') }
  : { docker: '/usr/bin/docker', python: '/usr/bin/python3' };
const projectName = process.env.DOWNLOAD_FLOW_COMPOSE_PROJECT
  ?? `batch-downloader-download-flow-${process.pid}`;
const applicationName = 'Codex Download Flow Smoke';
const officialPage = 'https://8.8.8.8/manual';
const timeoutMs = 20 * 60 * 1000;
const replicaSmoke = process.env.DOWNLOAD_FLOW_REPLICAS === '2';

assert.match(projectName, /^[a-z0-9][a-z0-9_-]*$/,
  'DOWNLOAD_FLOW_COMPOSE_PROJECT must be a lowercase Compose project name');

const composeEnvironment = {
  ...process.env,
  COMPOSE_PROFILES: '',
  MYSQL_ROOT_PASSWORD: 'download-flow-root-test',
  MYSQL_DATABASE: 'batch_downloader',
  MYSQL_USER: 'batch_downloader',
  MYSQL_PASSWORD: 'download-flow-mysql-test',
  MYSQL_HOST_PORT: '0',
  POSTGRES_HOST_PORT: '0',
  RABBITMQ_DEFAULT_USER: 'batch_downloader',
  RABBITMQ_DEFAULT_PASS: 'download-flow-rabbit-test',
  RABBITMQ_HOST_PORT: '0',
  RABBITMQ_MANAGEMENT_HOST_PORT: '0',
  MINIO_ROOT_USER: 'batch_downloader',
  MINIO_ROOT_PASSWORD: 'download-flow-minio-test',
  MINIO_CORE_ACCESS_KEY: 'batch_core',
  MINIO_CORE_SECRET_KEY: 'download-flow-core-minio-test',
  MINIO_WORKER_ACCESS_KEY: 'batch_worker',
  MINIO_WORKER_SECRET_KEY: 'download-flow-worker-minio-test',
  MINIO_API_HOST_PORT: '0',
  MINIO_CONSOLE_HOST_PORT: '0',
  WEBAPP_HOST_PORT: '0',
  CORE_API_HOST_PORT: '0',
  SEMANTIC_SERVICE_HOST_PORT: '0',
  NOTIFICATION_SERVICE_HOST_PORT: '0',
  DOWNLOAD_WORKER_HOST_PORT: '0',
  TRANSLATION_SERVICE_HOST_PORT: '0',
  APP_PUBLIC_BASE_URL: 'http://localhost',
  NOTIFICATION_TOKEN_ENCRYPTION_KEY: Buffer.alloc(32, 1).toString('base64'),
  SCRAPER_INTERNAL_SERVICE_TOKEN: 'download-flow-internal-test-token',
  SCRAPER_URL_PROTECTION_SECRET: 'download-flow-url-protection-test-secret-32-bytes',
  CORE_API_ADMIN_PASSWORD: 'Download-flow-admin-test-password-42!',
  CORE_API_DOWNLOAD_OWNER_SECRET: 'download-flow-anonymous-owner-test-secret',
  SCRAPER_LLM_GROQ_API_KEY: '',
  SCRAPER_LLM_DEEPSEEK_API_KEY: '',
  SCRAPER_LLM_OPENAI_API_KEY: '',
  CORE_API_REQUIRE_HTTPS: 'false',
  CORE_API_COOKIE_SECURE: 'false',
};

const composeArgs = [
  'compose',
  '--project-name', projectName,
  '--env-file', '.env.example',
  '-f', 'docker-compose.yml',
];
if (replicaSmoke) composeArgs.push('-f', 'docker-compose.scale.yml');

function compose(args, options = {}) {
  try {
    return execFileSync(executables.docker, [...composeArgs, ...args], {
      cwd: repositoryRoot,
      env: composeEnvironment,
      encoding: 'utf8',
      maxBuffer: 20 * 1024 * 1024,
      timeout: options.timeoutMs ?? timeoutMs,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    process.stderr.write(error.stdout?.toString() ?? '');
    process.stderr.write(error.stderr?.toString() ?? '');
    throw error;
  }
}

function executeMySql(sql) {
  const result = spawnSync(executables.docker, [
    ...composeArgs,
    'exec', '-T', 'mysql', 'sh', '-lc',
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u"$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --skip-column-names',
  ], {
    cwd: repositoryRoot,
    env: composeEnvironment,
    encoding: 'utf8',
    input: sql,
    maxBuffer: 1024 * 1024,
    timeout: 30_000,
  });
  if (result.status !== 0) {
    throw new Error(`Isolated MySQL command failed:\n${result.stderr || result.stdout}`);
  }
  return result.stdout.trim();
}

function verifyOperationalMetrics(workerHost = 'download-worker') {
  compose(['exec', '-T', 'scraper-api', 'python', '-c', `
import time
import urllib.request
def samples(text, name, labels=None):
    return [float(line.rsplit(' ', 1)[-1]) for line in text.splitlines()
            if (line.startswith(name + '{') or line.startswith(name + ' '))
            and all((key + '="' + value + '"') in line for key,value in (labels or {}).items())]
for attempt in range(60):
    core = urllib.request.urlopen('http://core-api:8080/actuator/prometheus', timeout=5).read().decode()
    worker = urllib.request.urlopen('http://${workerHost}:8080/actuator/prometheus', timeout=5).read().decode()
    def phase(phase_name, outcome):
        return any(value >= 1 for value in samples(worker, 'download_worker_phase_seconds_count',
                   {'phase':phase_name,'outcome':outcome}))
    if (phase('resolution', 'rejected') and phase('packaging', 'success')
            and any(value > 0 for value in samples(core, 'download_metrics_last_success_timestamp_seconds'))):
        assert samples(core, 'download_storage_limit_bytes') == [10 * 1024**3]
        assert samples(core, 'download_jobs', {'state':'FAILED'})
        break
    time.sleep(0.5)
else:
    raise AssertionError('Expected fresh Core and worker phase metrics were not exposed after the download')
`], { timeoutMs: 60_000 });
  process.stdout.write('Operational metrics were verified through the internal Prometheus endpoints.\n');
}

function containers(service) {
  return compose(['ps', '--quiet', service]).trim().split(/\s+/).filter(Boolean);
}

function docker(args) {
  return execFileSync(executables.docker, args, {
    cwd: repositoryRoot, encoding: 'utf8', timeout: 30_000, maxBuffer: 1024 * 1024,
  }).trim();
}

async function prepareReplicaRecovery(page, baseUrl, request) {
  assert.equal(containers('core-api').length, 2, 'two independent Core replicas must be healthy');
  // Un resolutor controlado mantiene una ventana suficiente para matar un dueño real.
  // Solo devuelve 404 tras la pausa: nunca permite descargar un binario ni llama a proveedores.
  compose(['exec', '--detach', 'scraper-api', 'python', '-c', `
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import time
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/healthz':
            self.send_response(200); self.end_headers(); return
        time.sleep(30)
        self.send_response(404); self.end_headers()
    def log_message(self,*args): pass
ThreadingHTTPServer(('0.0.0.0',8099),Handler).serve_forever()
`]);
  compose(['exec', '-T', 'scraper-api', 'python', '-c', `
import time,urllib.request
for attempt in range(50):
    try:
        urllib.request.urlopen('http://127.0.0.1:8099/healthz',timeout=1); break
    except OSError: time.sleep(.1)
else: raise AssertionError('Controlled resolver did not start')
`]);
  const csrf = await (await page.request.get(`${baseUrl}/api/v1/auth/csrf`)).json();
  const response = await page.request.post(`${baseUrl}/api/v1/download-jobs`, {
    data: request, headers: { 'X-XSRF-TOKEN': csrf.token },
  });
  assert.equal(response.status(), 202, 'the second concurrent job must be admitted');
  const second = await response.json();
  await page.evaluate(({ id, token }) => {
    window.setInterval(() => void fetch(`/api/v1/download-jobs/${id}/activity`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
      body: JSON.stringify({ phase: 'waiting' }),
    }).catch(() => {}), 10_000);
  }, { id: second.id, token: csrf.token });
  return second.id;
}

async function killBusyReplica() {
  const workers = containers('download-worker');
  assert.equal(workers.length, 2, 'two worker replicas must be healthy');
  const deadline = Date.now() + 20_000;
  let active = [];
  while (Date.now() < deadline) {
    active = workers.filter((id) => docker(['exec', id, 'curl', '-fsS',
      'http://127.0.0.1:8080/actuator/prometheus']).split('\n').some((line) =>
      /^download_worker_active_jobs(?:\{| )/.test(line) && Number(line.split(' ').at(-1)) >= 1));
    if (active.length === 2) break;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
  }
  assert.equal(active.length, 2, 'RabbitMQ must distribute two live jobs across both workers');
  const victim = active[0];
  // Solo IDs obtenidos de este proyecto aislado, nunca servicios del entorno del autor.
  assert.equal(docker(['inspect', '--format', '{{index .Config.Labels "com.docker.compose.project"}}', victim]), projectName);
  docker(['update', '--restart=no', victim]);
  docker(['kill', '--signal', 'KILL', victim]);
  const survivor = workers.find((id) => id !== victim);
  process.stdout.write('Both workers processed concurrently; one busy replica was killed for redelivery.\n');
  return docker(['inspect', '--format', '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', survivor]);
}

async function reserveHostPorts() {
  const keys = [
    'MYSQL_HOST_PORT', 'POSTGRES_HOST_PORT', 'RABBITMQ_HOST_PORT',
    'RABBITMQ_MANAGEMENT_HOST_PORT', 'MINIO_API_HOST_PORT',
    'MINIO_CONSOLE_HOST_PORT', 'WEBAPP_HOST_PORT',
    'CORE_API_HOST_PORT', 'SEMANTIC_SERVICE_HOST_PORT',
    'NOTIFICATION_SERVICE_HOST_PORT', 'DOWNLOAD_WORKER_HOST_PORT',
    'TRANSLATION_SERVICE_HOST_PORT',
  ];
  const servers = [];
  const values = {};
  try {
    await Promise.all(keys.map(async (key) => {
      const server = createServer();
      servers.push(server);
      await new Promise((resolveListen, rejectListen) => {
        server.once('error', rejectListen);
        server.listen(0, '127.0.0.1', resolveListen);
      });
      values[key] = String(server.address().port);
    }));
  } catch (error) {
    await Promise.all(servers.map((server) => new Promise((resolveClose) => server.close(resolveClose))));
    throw error;
  }
  return {
    values,
    close: () => Promise.all(
      servers.map((server) => new Promise((resolveClose) => server.close(resolveClose))),
    ),
  };
}

async function waitForHttp(url, timeout = 90_000) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(3_000) });
      if (response.ok) return;
      lastError = new Error(`${url} returned HTTP ${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 1_000));
  }
  throw new Error(`Timed out waiting for ${url}`, { cause: lastError });
}

function seedSelectableApplication() {
  const appId = randomUUID();
  const sourceId = randomUUID();
  const resolvedSourceId = randomUUID();
  const fingerprint = createHash('sha256').update(resolvedSourceId).digest('hex');
  const output = executeMySql(`
    INSERT INTO software_apps (
      id, winstall_id, slug, name, normalized_name, publisher, official_url,
      latest_version, app_status, operating_systems_json, version,
      created_at, updated_at
    ) VALUES (
      UUID_TO_BIN('${appId}'), 'Smoke.Download.Flow', 'codex-download-flow-smoke',
      '${applicationName}', 'codex download flow smoke', 'Codex test fixture',
      '${officialPage}', '1.0', 'active', JSON_ARRAY('windows'), 0,
      CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
    );
    INSERT INTO download_sources (
      id, software_app_id, operating_system, architecture, initial_url,
      resolver_type, resolution_status, validation_status, version, created_at,
      updated_at
    ) VALUES (
      UUID_TO_BIN('${sourceId}'), UUID_TO_BIN('${appId}'), 'windows', 'x86_64',
      'https://127.0.0.1/installer.exe', 'generic_http', 'direct', 'valid', 0,
      CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
    );
    INSERT INTO resolved_sources (
      id, download_source_id, resolved_url_encrypted, final_domain, filename,
      extension, content_type, size_bytes, version, release_rank, is_latest,
      score, status, validation_status, checked_at, expires_at, metadata_json,
      artifact_fingerprint
    ) VALUES (
      UUID_TO_BIN('${resolvedSourceId}'), UUID_TO_BIN('${sourceId}'),
      'invalidated-before-worker-start', '127.0.0.1', 'smoke-installer.exe',
      'exe', 'application/octet-stream', 1024, '1.0', 1, TRUE, 100, 'direct',
      'valid', CURRENT_TIMESTAMP(6), DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY),
      JSON_OBJECT('validation_confidence', 'validated'), '${fingerprint}'
    );
    SELECT IF(
      (SELECT catalog_status FROM software_apps WHERE id = UUID_TO_BIN('${appId}')) = 'available'
      AND (SELECT catalog_downloadable FROM resolved_sources WHERE id = UUID_TO_BIN('${resolvedSourceId}')) = 1,
      'seeded', 'seed-failed'
    );
  `);
  assert.equal(output.split(/\r?\n/).at(-1), 'seeded',
    'the fixture must be available and selectable before it is invalidated');
  return { appId, sourceId, resolvedSourceId };
}

function invalidateResolvedSource(resolvedSourceId) {
  const output = executeMySql(`
    UPDATE resolved_sources
    SET validation_status = 'expired',
        expires_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 MINUTE)
    WHERE id = UUID_TO_BIN('${resolvedSourceId}');
    SELECT IF(
      validation_status = 'expired' AND catalog_downloadable = 0,
      'invalidated', 'checked'
    ) FROM resolved_sources WHERE id = UUID_TO_BIN('${resolvedSourceId}');
  `);
  assert.equal(output.split(/\r?\n/).at(-1), 'invalidated',
    'the selected source must be invalidated before the worker starts');
}

function inspectZip(zipPath) {
  const python = String.raw`
import json
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = set(archive.namelist())
    manifest_name = next((name for name in names if name == "manifest.json" or name.endswith("/manifest.json")), None)
    assert manifest_name, f"manifest.json missing from ZIP: {sorted(names)}"
    manifest = json.loads(archive.read(manifest_name))
    assert manifest["status"] == "MANUAL_ONLY", manifest
    items = manifest["items"]
    assert len(items) == 1, items
    shortcut = items[0]["manualShortcut"]
    assert shortcut == "Descargas manuales/Codex Download Flow Smoke.url", shortcut
    assert shortcut in names, f"{shortcut} missing from ZIP: {sorted(names)}"
    content = archive.read(shortcut).decode("utf-8-sig")
    assert content == "[InternetShortcut]\r\nURL=https://8.8.8.8/manual\r\n", repr(content)
    print(json.dumps({"status": manifest["status"], "shortcut": shortcut, "files": sorted(names)}))
`;
  const result = spawnSync(executables.python, ['-c', python, zipPath], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    maxBuffer: 1024 * 1024,
    timeout: 30_000,
  });
  if (result.status !== 0) {
    throw new Error(`ZIP assertions failed:\n${result.stderr || result.stdout}`);
  }
  process.stdout.write(`Verified archive: ${result.stdout.trim()}\n`);
}

export async function run() {
  const temporaryDirectory = mkdtempSync(resolve(tmpdir(), 'batch-downloader-manual-smoke-'));
  if (replicaSmoke) {
    const override = resolve(temporaryDirectory, 'replica-resolver.yml');
    writeFileSync(override, 'services:\n  download-worker:\n    environment:\n'
      + '      DOWNLOAD_WORKER_SOURCE_RESOLVER_URL: http://scraper-api:8099\n'
      + '      DOWNLOAD_WORKER_SOURCE_RESOLVER_TIMEOUT: 45s\n');
    composeArgs.push('-f', override);
  }
  let browser;
  let reservations;
  let primaryError;
  try {
    reservations = await reserveHostPorts();
    Object.assign(composeEnvironment, reservations.values);
    const baseUrl = `http://127.0.0.1:${reservations.values.WEBAPP_HOST_PORT}`;
    composeEnvironment.APP_PUBLIC_BASE_URL = baseUrl;

    browser = await chromium.launch({ headless: true });
    process.stdout.write(`Building the isolated Compose project ${projectName}.\n`);
    compose([
      'build', 'scraper-api', 'core-api', 'webapp', 'download-worker', 'translation-service',
    ]);
    await reservations.close();
    reservations = undefined;

    process.stdout.write(
      'Starting MySQL, RabbitMQ, MinIO, Scraper API, Core API, translations and webapp.\n',
    );
    compose(['up', '--detach', '--wait', '--wait-timeout', '900',
      ...(replicaSmoke ? ['--scale', 'core-api=2'] : []),
      'mysql', 'rabbitmq', 'minio', 'scraper-api', 'core-api', 'translation-service', 'webapp'],
    { timeoutMs: 20 * 60 * 1000 });
    await waitForHttp(`${baseUrl}/healthz`);

    const seeded = seedSelectableApplication();
    const page = await browser.newPage({ locale: 'es-ES', acceptDownloads: true });
    await page.addInitScript(() => {
      Object.defineProperty(window, 'showSaveFilePicker', { configurable: true, value: undefined });
    });
    await page.goto(`${baseUrl}/catalog`, { waitUntil: 'domcontentloaded' });

    const selection = page.getByRole('checkbox', { name: `Seleccionar ${applicationName}` });
    await selection.waitFor({ state: 'visible', timeout: 90_000 });
    assert.equal(await selection.isEnabled(), true,
      'the valid source must make the application selectable before the worker starts');
    await selection.check();

    const createdJobResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return response.request().method() === 'POST'
        && url.pathname === '/api/v1/download-jobs';
    });
    await page.getByRole('button', { name: 'Descargar ZIP' }).click();
    const response = await createdJobResponse;
    assert.equal(response.status(), 202, `job creation returned HTTP ${response.status()}`);
    const createdJob = await response.json();
    assert.ok(createdJob.id, 'Core API must return the created job identifier');

    const secondJobId = replicaSmoke
      ? await prepareReplicaRecovery(page, baseUrl, response.request().postDataJSON()) : undefined;

    invalidateResolvedSource(seeded.resolvedSourceId);
    const automaticDownload = page.waitForEvent('download', { timeout: 90_000 }).catch(() => null);
    process.stdout.write('Starting the worker only after invalidating the selected source.\n');
    compose(['up', '--detach', '--wait', '--wait-timeout', '600',
      ...(replicaSmoke ? ['--scale', 'core-api=2', '--scale', 'download-worker=2'] : []), 'download-worker'],
      { timeoutMs: 15 * 60 * 1000 });
    const survivorHost = replicaSmoke ? await killBusyReplica() : 'download-worker';

    await page.waitForFunction(async (jobId) => {
      const response = await fetch(`/api/v1/download-jobs/${encodeURIComponent(jobId)}`);
      if (!response.ok) return false;
      return (await response.json()).status === 'MANUAL_ONLY';
    }, createdJob.id, { timeout: 180_000 });
    if (secondJobId) {
      await page.waitForFunction(async (id) => {
        const response = await fetch(`/api/v1/download-jobs/${id}`);
        return response.ok && (await response.json()).status === 'MANUAL_ONLY';
      }, secondJobId, { timeout: 180_000 });
      let completed;
      for (let attempt = 0; attempt < 150; attempt++) {
        completed = executeMySql("SELECT COUNT(*) FROM download_inbox WHERE status='COMPLETED';");
        if (completed === '2') break;
        await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
      }
      // READY puede llegar a Core antes del último callback y del ACK del worker.
      // La limpieza puede haber borrado job_id sin retirar la deduplicación completada.
      assert.equal(completed, '2', 'both requests must eventually complete their shared inbox entries');
      process.stdout.write('The surviving worker completed both jobs, including the redelivered command.\n');
    }
    await page.getByText('Accesos manuales preparados', { exact: true })
      .waitFor({ state: 'visible', timeout: 30_000 });

    let download = await automaticDownload;
    if (!download) {
      const downloadButton = page.getByRole('button', { name: 'Obtener ZIP' });
      await downloadButton.waitFor({ state: 'visible', timeout: 30_000 });
      const explicitDownload = page.waitForEvent('download', { timeout: 120_000 });
      await downloadButton.click();
      download = await explicitDownload;
    }

    const zipPath = resolve(temporaryDirectory, 'manual-only.zip');
    await download.saveAs(zipPath);
    inspectZip(zipPath);
    verifyOperationalMetrics(survivorHost);
    process.stdout.write('The UI download completed with a MANUAL_ONLY manifest and a safe .url entry.\n');
  } catch (error) {
    primaryError = error;
    try {
      process.stderr.write('\nCompose service logs from the failed smoke test:\n');
      process.stderr.write(compose([
        'logs', '--tail', '25', '--no-color', 'scraper-api', 'core-api', 'download-worker', 'webapp',
      ]));
    } catch (logError) {
      process.stderr.write(`Unable to collect Compose logs: ${logError.message}\n`);
    }
  }
  const cleanupResults = await Promise.allSettled([
    browser?.close(),
    reservations?.close(),
    Promise.resolve().then(() => {
      const cleanup = spawnSync(executables.docker, [...composeArgs, 'down', '--volumes', '--remove-orphans'], {
        cwd: repositoryRoot,
        env: composeEnvironment,
        encoding: 'utf8',
        maxBuffer: 5 * 1024 * 1024,
        timeout: 180_000,
      });
      rmSync(temporaryDirectory, { recursive: true, force: true });
      if (cleanup.status !== 0) {
        process.stderr.write(cleanup.stderr || cleanup.stdout || 'Compose cleanup failed.\n');
        throw new Error('Failed to remove the isolated Compose project');
      }
    }),
  ]);
  for (const result of cleanupResults) {
    if (result.status === 'rejected') {
      process.stderr.write(`Cleanup failed: ${result.reason}\n`);
      primaryError ??= result.reason;
    }
  }
  if (primaryError) throw primaryError;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    await run();
  } catch (error) {
    process.stderr.write(`${error.stack ?? error}\n`);
    process.exitCode = 1;
  }
}
