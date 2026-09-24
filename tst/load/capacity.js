import http from 'k6/http';
import exec from 'k6/execution';
import { browser } from 'k6/browser';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
const APP_IDS = csv(__ENV.APP_IDS);
const READY_JOB_IDS = csv(__ENV.READY_JOB_IDS);
const MAGIC_LINK_TOKENS = parseJsonArray(__ENV.MAGIC_LINK_TOKENS);
const FINAL_MAGIC_LINK_TOKENS = parseJsonArray(__ENV.FINAL_MAGIC_LINK_TOKENS);
const FINAL_RANGE = __ENV.FINAL_RANGE || '';
const SESSION_VUS = Number(__ENV.SESSION_VUS || 100);
const JOB_VUS = Number(__ENV.JOB_VUS || 12);

const navigationDuration = new Trend('navigation_duration', true);
const jobCreationDuration = new Trend('job_creation_duration', true);
const zipMetadataDuration = new Trend('zip_metadata_duration', true);
const capacityErrors = new Rate('capacity_errors');
const unexpectedErrors = new Rate('unexpected_errors');
const sseHeartbeats = new Counter('sse_heartbeats');
const sseJobEvents = new Counter('sse_job_events');

const scenarios = {
  web_sessions: {
    executor: 'constant-vus',
    exec: 'webSession',
    vus: SESSION_VUS,
    duration: __ENV.WEB_DURATION || '15m',
    gracefulStop: '30s',
  },
  jobs_and_sse: {
    executor: 'per-vu-iterations',
    exec: 'jobAndSse',
    vus: JOB_VUS,
    iterations: 1,
    startTime: '1m',
    maxDuration: __ENV.JOB_MAX_DURATION || '15m',
    gracefulStop: '30s',
    options: { browser: { type: 'chromium' } },
  },
};

if (READY_JOB_IDS.length > 0) {
  scenarios.final_deliveries = {
    executor: 'per-vu-iterations',
    exec: 'finalDelivery',
    vus: JOB_VUS,
    iterations: 1,
    startTime: __ENV.FINAL_START_TIME || '0s',
    maxDuration: __ENV.FINAL_MAX_DURATION || '6h',
    gracefulStop: '30s',
  };
}

const thresholds = {
  'http_req_duration{traffic:api}': ['p(95)<750'],
  'http_req_failed{traffic:api}': ['rate<0.01'],
  navigation_duration: ['p(95)<750'],
  job_creation_duration: ['p(95)<750'],
  capacity_errors: ['rate<0.01'],
  unexpected_errors: ['rate<0.01'],
  checks: ['rate>0.99'],
};
if (READY_JOB_IDS.length > 0) {
  thresholds.zip_metadata_duration = ['p(95)<750'];
}

export const options = {
  discardResponseBodies: true,
  scenarios,
  thresholds,
};

/** Mantiene cookies independientes; cada VU realiza aproximadamente una petición cada diez segundos. */
export function webSession() {
  if (__ITER === 0) {
    // Escalona la creación de sesiones en diez segundos y empieza el tráfico
    // diez segundos después para no sumar ambas ráfagas.
    sleep(((__VU - 1) % 100) / 10);
    const response = http.get(`${BASE_URL}/api/v1/auth/csrf`, apiParams('session_init'));
    record(response, [200]);
    check(response, { 'session cookie is established': (value) => value.status === 200 });
    sleep(10);
    return;
  }

  group('navegación pública', () => {
    const choice = Math.random();
    const path = choice < 0.55
      ? '/api/v1/apps?status=available&page=1&pageSize=20&sort=name'
      : choice < 0.80
        ? '/api/v1/apps/stats'
        : '/api/v1/bundles?page=1&pageSize=12&sort=updated';
    const response = http.get(`${BASE_URL}${path}`, apiParams('navigation'));
    navigationDuration.add(response.timings.duration);
    record(response, [200]);
    check(response, { 'navigation response is usable': (value) => value.status === 200 });
  });
  sleep(9 + Math.random() * 2);
}

/** Crea jobs autenticados y conserva SSE y actividad mientras esperan o se preparan. */
export async function jobAndSse() {
  requireJobInputs();
  const account = exec.scenario.iterationInTest + 1;
  const page = await browser.newPage();
  try {
    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    const result = await page.evaluate(async (input) => {
      const csrf = async () => {
        const response = await fetch('/api/v1/auth/csrf', { credentials: 'include' });
        return { status: response.status, body: await response.json() };
      };

      const initial = await csrf();
      if (initial.status !== 200) {
        return { status: initial.status, stage: 'csrf', heartbeats: 0, jobs: 0 };
      }
      const magicLink = await fetch('/api/v1/auth/magic-link/confirm', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': initial.body.token },
        body: JSON.stringify({ token: input.magicToken }),
      });
      if (magicLink.status !== 200) {
        return { status: magicLink.status, stage: 'magic_link', heartbeats: 0, jobs: 0 };
      }

      const authenticated = await csrf();
      const createdAt = performance.now();
      const created = await fetch('/api/v1/download-jobs', {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'X-XSRF-TOKEN': authenticated.body.token,
        },
        body: JSON.stringify({
          appIds: [input.appId],
          operatingSystems: ['windows', 'linux', 'macos'],
        }),
      });
      const creationMs = performance.now() - createdAt;
      if (created.status !== 202) {
        return {
          status: created.status,
          stage: 'create',
          creationMs,
          heartbeats: 0,
          jobs: 0,
        };
      }

      const job = await created.json();
      return new Promise((resolve) => {
        let heartbeats = 0;
        let jobs = 0;
        let preparing = true;
        const source = new EventSource(`/api/v1/download-jobs/${job.id}/events`, {
          withCredentials: true,
        });
        source.addEventListener('heartbeat', () => { heartbeats += 1; });
        source.addEventListener('job', (event) => {
          jobs += 1;
          preparing = ['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PACKAGING'].includes(JSON.parse(event.data).status);
        });
        const activity = window.setInterval(() => {
          if (!preparing) return;
          void fetch(`/api/v1/download-jobs/${job.id}/activity`, {
            method: 'POST', credentials: 'include',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': authenticated.body.token },
            body: JSON.stringify({ phase: 'waiting' }),
          }).catch(() => undefined);
        }, 15000);
        const finish = () => {
          window.clearInterval(activity);
          window.clearTimeout(timeout);
          source.close();
          resolve({ status: 202, stage: 'sse', creationMs, heartbeats, jobs });
        };
        const timeout = window.setTimeout(finish, input.durationMs);
        source.addEventListener('removed', finish);
      });
    }, {
      magicToken: MAGIC_LINK_TOKENS[account - 1] || '',
      appId: APP_IDS[(account - 1) % APP_IDS.length],
      durationMs: Number(__ENV.SSE_DURATION_MS || 610000),
    });

    if (result.creationMs !== undefined) jobCreationDuration.add(result.creationMs);
    capacityErrors.add(result.status === 503);
    unexpectedErrors.add(result.status !== 202);
    sseHeartbeats.add(result.heartbeats);
    sseJobEvents.add(result.jobs);
    check(result, {
      'job is accepted': (value) => value.status === 202,
      'SSE receives heartbeats': (value) => value.heartbeats > 0,
      'SSE receives job state': (value) => value.jobs > 0,
    });
  } finally {
    await page.close();
  }
}

/** Comprueba HEAD y transfiere el ZIP mediante la ruta autorizada y observable de Core. */
export function finalDelivery() {
  requireFinalInputs();
  const account = exec.scenario.iterationInTest + 1;
  if (!login(account, FINAL_MAGIC_LINK_TOKENS)) return;

  const jobId = READY_JOB_IDS[account - 1];
  const metadata = http.head(
    `${BASE_URL}/api/v1/download-jobs/${jobId}/file`,
    apiParams('zip_metadata'),
  );
  zipMetadataDuration.add(metadata.timings.duration);
  record(metadata, [200]);
  check(metadata, {
    'Core exposes ZIP metadata': (value) => value.status === 200,
    'ZIP supports ranges': (value) => value.headers['Accept-Ranges'] === 'bytes',
  });
  if (metadata.status !== 200) return;

  const headers = FINAL_RANGE ? { Range: FINAL_RANGE } : {};
  const transfer = http.get(`${BASE_URL}/api/v1/download-jobs/${jobId}/file`, {
    headers,
    tags: { endpoint: 'final_transfer', traffic: 'artifact' },
    timeout: __ENV.FINAL_TRANSFER_TIMEOUT || '6h',
    responseType: 'none',
  });
  record(transfer, FINAL_RANGE ? [206] : [200]);
  check(transfer, {
    'Core serves the requested artifact bytes': (value) => FINAL_RANGE
      ? value.status === 206
      : value.status === 200,
    'ZIP content type is present': (value) => String(value.headers['Content-Type'] || '')
      .toLowerCase().includes('application/zip'),
    'safe filename is present': (value) => String(value.headers['Content-Disposition'] || '')
      .includes(`batch-downloader-${jobId}.zip`),
  });
}

function login(account, tokens) {
  const token = csrfToken();
  const magicToken = tokens[account - 1] || '';
  if (!token || !magicToken) return false;
  const response = http.post(
    `${BASE_URL}/api/v1/auth/magic-link/confirm`,
    JSON.stringify({ token: magicToken }),
    { ...apiParams('auth'), headers: jsonHeaders(token) },
  );
  record(response, [200]);
  check(response, { 'login is accepted': (value) => value.status === 200 });
  return response.status === 200;
}

function parseJsonArray(value) {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

function csrfToken() {
  const response = http.get(`${BASE_URL}/api/v1/auth/csrf`, {
    ...apiParams('csrf'),
    responseType: 'text',
  });
  record(response, [200]);
  check(response, { 'CSRF token is available': (value) => value.status === 200 });
  return response.status === 200 ? response.json().token : null;
}

function apiParams(endpoint) {
  return {
    tags: { endpoint, traffic: 'api' },
    timeout: '5s',
    responseType: 'text',
  };
}

function jsonHeaders(csrf) {
  return { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf };
}

function record(response, acceptedStatuses) {
  const accepted = acceptedStatuses.includes(response.status);
  capacityErrors.add(response.status === 503);
  unexpectedErrors.add(!accepted);
}

function requireJobInputs() {
  if (APP_IDS.length < 2) {
    exec.test.abort('APP_IDS must contain at least two controlled application IDs');
  }
  if (MAGIC_LINK_TOKENS.length < JOB_VUS) {
    exec.test.abort('MAGIC_LINK_TOKENS must contain one current token for each job VU');
  }
}

function requireFinalInputs() {
  requireJobInputs();
  if (FINAL_MAGIC_LINK_TOKENS.length !== JOB_VUS) {
    exec.test.abort('FINAL_MAGIC_LINK_TOKENS must contain one current token for each final-delivery VU');
  }
  if (READY_JOB_IDS.length !== JOB_VUS) {
    exec.test.abort('READY_JOB_IDS must contain JOB_VUS jobs in owner order');
  }
}

function csv(value) {
  return (value || '').split(',').map((entry) => entry.trim()).filter(Boolean);
}
