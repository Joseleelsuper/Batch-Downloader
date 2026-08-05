import http from 'k6/http';
import exec from 'k6/execution';
import { browser } from 'k6/browser';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/$/, '');
const APP_IDS = (__ENV.APP_IDS || '').split(',').map((value) => value.trim()).filter(Boolean);
const USERNAME_PREFIX = __ENV.USERNAME_PREFIX || '';
const USER_PASSWORD = __ENV.USER_PASSWORD || '';
const navigationBaseline = new Trend('navigation_baseline', true);
const navigationContended = new Trend('navigation_contended', true);
const jobCreation = new Trend('job_creation', true);
const authDuration = new Trend('auth_duration', true);
const capacityErrors = new Rate('capacity_errors');
const sseHeartbeats = new Counter('sse_heartbeats');
const sseJobEvents = new Counter('sse_job_events');
const scenarioStartedAt = Date.now();

export const options = {
  discardResponseBodies: true,
  scenarios: {
    navigation_target: {
      executor: 'constant-vus',
      exec: 'navigation',
      vus: 100,
      duration: '15m',
      gracefulStop: '30s',
    },
    zip_burst: {
      executor: 'shared-iterations',
      exec: 'zipBurst',
      vus: 20,
      iterations: 20,
      startTime: '1m',
      maxDuration: '10s',
    },
    zip_lifecycle: {
      executor: 'per-vu-iterations',
      exec: 'zipLifecycle',
      vus: 2,
      iterations: 1,
      startTime: '1m',
      maxDuration: '59m',
    },
    sse_browser: {
      executor: 'shared-iterations',
      exec: 'sseBrowser',
      vus: 2,
      iterations: 2,
      startTime: '2m',
      maxDuration: '12m',
      options: { browser: { type: 'chromium' } },
    },
    soak: {
      executor: 'constant-vus',
      exec: 'navigation',
      vus: 20,
      startTime: '15m',
      duration: '60m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'http_req_duration{endpoint:navigation}': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed{endpoint:navigation}': ['rate<0.01'],
    job_creation: ['p(95)<500'],
    auth_duration: ['p(95)<2000'],
    capacity_errors: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

function navigationRequest(path) {
  const response = http.get(`${BASE_URL}${path}`, {
    tags: { endpoint: 'navigation' },
    timeout: '2s',
  });
  const phaseTrend = Date.now() - scenarioStartedAt < 60_000
    ? navigationBaseline
    : navigationContended;
  phaseTrend.add(response.timings.duration);
  capacityErrors.add(response.status === 503);
  check(response, { 'navigation response is usable': (value) => value.status === 200 });
}

export function navigation() {
  group('public navigation', () => {
    const choice = Math.random();
    if (choice < 0.55) {
      navigationRequest('/api/v1/apps?status=available&page=1&pageSize=20&sort=name');
    } else if (choice < 0.8) {
      navigationRequest('/api/v1/apps/stats');
    } else {
      navigationRequest('/api/v1/bundles?page=1&pageSize=12&sort=updated');
    }
  });
  if (USERNAME_PREFIX && USER_PASSWORD && __ITER % 20 === 0) {
    const jar = http.cookieJar();
    const csrf = csrfToken(jar);
    const response = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ username: `${USERNAME_PREFIX}${__VU}`, password: USER_PASSWORD }),
      jsonParams(csrf, { endpoint: 'auth' }),
    );
    authDuration.add(response.timings.duration);
    check(response, { 'login is accepted': (value) => value.status === 200 });
  }
  sleep(0.2 + Math.random() * 0.8);
}

export function zipBurst() {
  requireControlledSources();
  const jar = http.cookieJar();
  const response = createJob(jar, APP_IDS[__ITER % APP_IDS.length]);
  check(response, { 'ZIP burst returns 202': (value) => value.status === 202 });
}

export function zipLifecycle() {
  requireControlledSources();
  const jar = http.cookieJar();
  const created = createJob(jar, APP_IDS[(__VU - 1) % APP_IDS.length]);
  if (created.status !== 202) return;
  const job = created.json();
  for (let attempt = 0; attempt < 660; attempt += 1) {
    const status = http.get(`${BASE_URL}/api/v1/download-jobs/${job.id}`, {
      tags: { endpoint: 'job_status' },
      timeout: '2s',
      responseType: 'text',
    });
    const body = status.json();
    if (['READY', 'PARTIAL', 'MANUAL_ONLY'].includes(body.status)) {
      const redirect = http.get(`${BASE_URL}/api/v1/download-jobs/${job.id}/file`, {
        redirects: 0,
        tags: { endpoint: 'zip_redirect' },
      });
      check(redirect, { 'Core redirects ZIP with 303': (value) => value.status === 303 });
      const signedUrl = redirect.headers.Location;
      if (signedUrl) {
        const range = http.get(signedUrl, {
          headers: { Range: 'bytes=0-1048575' },
          tags: { endpoint: 'minio_range' },
          timeout: '30s',
        });
        check(range, { 'MinIO serves direct range': (value) => [200, 206].includes(value.status) });
      }
      return;
    }
    if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(body.status)) return;
    sleep(5);
  }
}

export async function sseBrowser() {
  requireControlledSources();
  const page = await browser.newPage();
  try {
    await page.goto(BASE_URL, { waitUntil: 'networkidle' });
    const result = await page.evaluate(async ({ appId, durationMs }) => {
      const csrfResponse = await fetch('/api/v1/auth/csrf', { credentials: 'include' });
      const csrf = await csrfResponse.json();
      const created = await fetch('/api/v1/download-jobs', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf.token },
        body: JSON.stringify({ appIds: [appId], operatingSystems: ['windows', 'linux', 'macos'] }),
      });
      if (created.status !== 202) return { status: created.status, heartbeats: 0, jobs: 0 };
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
          resolve({ status: 202, heartbeats, jobs });
        }, durationMs);
      });
    }, {
      appId: APP_IDS[(__VU - 1) % APP_IDS.length],
      durationMs: Number(__ENV.SSE_DURATION_MS || 610000),
    });
    sseHeartbeats.add(result.heartbeats);
    sseJobEvents.add(result.jobs);
    check(result, {
      'SSE job was created': (value) => value.status === 202,
      'SSE receives heartbeats': (value) => value.heartbeats > 0,
      'SSE receives job state': (value) => value.jobs > 0,
    });
  } finally {
    await page.close();
  }
}

function createJob(jar, appId) {
  const token = csrfToken(jar);
  const response = http.post(
    `${BASE_URL}/api/v1/download-jobs`,
    JSON.stringify({ appIds: [appId], operatingSystems: ['windows', 'linux', 'macos'] }),
    jsonParams(token, { endpoint: 'job_creation' }),
  );
  jobCreation.add(response.timings.duration);
  capacityErrors.add(response.status === 503);
  return response;
}

function csrfToken(jar) {
  const response = http.get(`${BASE_URL}/api/v1/auth/csrf`, {
    jar,
    tags: { endpoint: 'csrf' },
    responseType: 'text',
  });
  check(response, { 'CSRF token is available': (value) => value.status === 200 });
  return response.json().token;
}

function jsonParams(csrf, tags) {
  return {
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
    tags,
    timeout: '3s',
    responseType: 'text',
  };
}

function requireControlledSources() {
  if (APP_IDS.length < 2) {
    exec.test.abort('APP_IDS must contain two controlled 1-2 GB application IDs');
  }
}
