import http from 'k6/http';
import exec from 'k6/execution';
import { browser } from 'k6/browser';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
const APP_IDS = csv(__ENV.APP_IDS);
const READY_JOB_IDS = csv(__ENV.READY_JOB_IDS);
const USERNAME_PREFIX = __ENV.USERNAME_PREFIX || '';
const USER_PASSWORD = __ENV.USER_PASSWORD || '';
const FINAL_RANGE = __ENV.FINAL_RANGE || '';
const SESSION_VUS = 1000;
const JOB_VUS = 50;

const navigationDuration = new Trend('navigation_duration', true);
const jobCreationDuration = new Trend('job_creation_duration', true);
const signedRedirectDuration = new Trend('signed_redirect_duration', true);
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
    startTime: __ENV.FINAL_START_TIME || '2m',
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
  thresholds.signed_redirect_duration = ['p(95)<750'];
}

export const options = {
  discardResponseBodies: true,
  scenarios,
  thresholds,
};

/** Mantiene 1.000 cookies de sesión y genera aproximadamente 100 requests/s. */
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

/** Crea 50 jobs autenticados y conserva una conexión SSE por job. */
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
      const login = await fetch('/api/v1/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': initial.body.token },
        body: JSON.stringify({ username: input.username, password: input.password }),
      });
      if (login.status !== 200) {
        return { status: login.status, stage: 'login', heartbeats: 0, jobs: 0 };
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
        const source = new EventSource(`/api/v1/download-jobs/${job.id}/events`, {
          withCredentials: true,
        });
        source.addEventListener('heartbeat', () => { heartbeats += 1; });
        source.addEventListener('job', () => { jobs += 1; });
        window.setTimeout(() => {
          source.close();
          resolve({ status: 202, stage: 'sse', creationMs, heartbeats, jobs });
        }, input.durationMs);
      });
    }, {
      username: `${USERNAME_PREFIX}${account}`,
      password: USER_PASSWORD,
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

/** Lanza 50 GET directos a URLs firmadas sin transportar el ZIP por Core. */
export function finalDelivery() {
  requireFinalInputs();
  const account = exec.scenario.iterationInTest + 1;
  if (!login(account)) return;

  const jobId = READY_JOB_IDS[account - 1];
  const redirect = http.get(
    `${BASE_URL}/api/v1/download-jobs/${jobId}/file`,
    { ...apiParams('zip_redirect'), redirects: 0 },
  );
  signedRedirectDuration.add(redirect.timings.duration);
  record(redirect, [303]);
  check(redirect, {
    'Core responds with 303': (value) => value.status === 303,
    'signed URL is present': (value) => Boolean(value.headers.Location),
  });
  if (redirect.status !== 303 || !redirect.headers.Location) return;

  const headers = FINAL_RANGE ? { Range: FINAL_RANGE } : {};
  const transfer = http.get(redirect.headers.Location, {
    headers,
    tags: { endpoint: 'final_transfer', traffic: 'artifact' },
    timeout: __ENV.FINAL_TRANSFER_TIMEOUT || '6h',
    responseType: 'none',
  });
  record(transfer, FINAL_RANGE ? [200, 206] : [200]);
  check(transfer, {
    'MinIO serves the artifact directly': (value) => FINAL_RANGE
      ? [200, 206].includes(value.status)
      : value.status === 200,
    'ZIP content type is signed': (value) => String(value.headers['Content-Type'] || '')
      .toLowerCase().includes('application/zip'),
    'safe filename is signed': (value) => String(value.headers['Content-Disposition'] || '')
      .includes(`batch-downloader-${jobId}.zip`),
  });
}

function login(account) {
  const token = csrfToken();
  if (!token) return false;
  const response = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: `${USERNAME_PREFIX}${account}`, password: USER_PASSWORD }),
    { ...apiParams('auth'), headers: jsonHeaders(token) },
  );
  record(response, [200]);
  check(response, { 'login is accepted': (value) => value.status === 200 });
  return response.status === 200;
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
  if (!USERNAME_PREFIX || !USER_PASSWORD) {
    exec.test.abort('USERNAME_PREFIX and USER_PASSWORD are required for 50 jobs');
  }
}

function requireFinalInputs() {
  requireJobInputs();
  if (READY_JOB_IDS.length !== JOB_VUS) {
    exec.test.abort('READY_JOB_IDS must contain exactly 50 jobs owned by accounts 1..50');
  }
}

function csv(value) {
  return (value || '').split(',').map((entry) => entry.trim()).filter(Boolean);
}
