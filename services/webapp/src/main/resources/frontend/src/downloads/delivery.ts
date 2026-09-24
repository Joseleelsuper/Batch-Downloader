import { completeDownloadJob, reportDownloadActivity } from '../api/downloads';
import { ApiRequestError, apiFetch } from '../api/http';
import { responseError } from '../api/http/errors';
import type { DownloadJob } from '../types/catalog';

// The browser API is not yet included in TypeScript's DOM declarations.
export interface DownloadFileHandle {
  createWritable(): Promise<{
    write(data: Uint8Array): Promise<void>;
    close(): Promise<void>;
    abort(reason?: unknown): Promise<void>;
  }>;
}

export function chooseDownloadDestination(): Promise<DownloadFileHandle | undefined> {
  const picker = (window as Window & {
    showSaveFilePicker?: (options: object) => Promise<DownloadFileHandle>;
  }).showSaveFilePicker;
  return picker ? picker.call(window, {
    suggestedName: 'batch-downloader.zip',
    types: [{ description: 'ZIP', accept: { 'application/zip': ['.zip'] } }],
  }) : Promise.resolve(undefined);
}

/** Short bounded recovery for reconciliation, gateway failures and lost responses. */
export async function retryDeliveryRequest<T>(request: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await request();
    } catch (cause) {
      const transient = cause instanceof TypeError
        || (cause instanceof DOMException && ['AbortError', 'TimeoutError'].includes(cause.name))
        || (cause instanceof ApiRequestError && (
        [500, 502, 504].includes(cause.status)
        || (cause.status === 409 && cause.code === 'download_not_ready')
        || (cause.status === 503 && ['storage_reconciling', 'download_unavailable', 'database_unavailable'].includes(cause.code))
      ));
      if (!transient || attempt >= 3) throw cause;
      const retryAfter = cause instanceof ApiRequestError && cause.retryAfter
        ? Number(cause.retryAfter) || (Date.parse(cause.retryAfter) - Date.now()) / 1000 : 0;
      const delay = Math.min(10_000, Math.max(1000 * 2 ** attempt, Number.isFinite(retryAfter) ? retryAfter * 1000 : 0));
      await new Promise<void>((resolve) => window.setTimeout(resolve, delay));
    }
  }
}

/** Writes each received chunk before reading another; never buffers the ZIP. */
export async function saveDownload(
  job: DownloadJob, handle: DownloadFileHandle, onBytes: (bytes: number) => void,
  onSaved: () => void = () => undefined,
): Promise<void> {
  const expected = job.artifactSizeBytes;
  if (!Number.isSafeInteger(expected) || !expected || expected < 0) {
    throw new Error('download_size_unavailable');
  }
  const writable = await handle.createWritable();
  let received = 0;
  const activity = () => reportDownloadActivity(job.id, 'saving', received);
  let activityPending = false;
  const heartbeat = window.setInterval(() => {
    if (activityPending) return;
    activityPending = true;
    void activity().catch(() => undefined).finally(() => { activityPending = false; });
  }, 15_000);
  try {
    await retryDeliveryRequest(activity);
    // A transient network interruption can resume the same open file with Range.
    for (let attempt = 0; received < expected; attempt++) {
      const response = await retryDeliveryRequest(async () => {
        const result = await apiFetch(`/api/v1/download-jobs/${encodeURIComponent(job.id)}/file`, {
          headers: received ? { Range: `bytes=${received}-` } : undefined,
          timeoutMs: 10_000,
        });
        if (!result.ok) throw await responseError(result);
        return result;
      });
      if ((received && response.status !== 206)
          || (response.status === 206
            && response.headers.get('Content-Range') !== `bytes ${received}-${expected - 1}/${expected}`)) {
        await response.body?.cancel();
        throw new Error('download_range_mismatch');
      }
      if (!response.body) throw new Error('download_body_missing');
      const reader = response.body.getReader();
      try {
        while (true) {
          let chunk: ReadableStreamReadResult<Uint8Array>;
          try {
            chunk = await reader.read();
          } catch (cause) {
            if (attempt >= 2) throw cause;
            break;
          }
          if (chunk.done) break;
          if (received + chunk.value.byteLength > expected) throw new Error('download_size_mismatch');
          await writable.write(chunk.value);
          received += chunk.value.byteLength;
          onBytes(received);
        }
      } finally {
        await reader.cancel().catch(() => undefined);
        reader.releaseLock();
      }
      if (received !== expected && attempt >= 2) throw new Error('download_size_mismatch');
    }
    await writable.close();
    onSaved();
  } catch (cause) {
    await writable.abort(cause).catch(() => undefined);
    throw cause;
  } finally {
    window.clearInterval(heartbeat);
  }
  // A receipt is sent only after the browser has committed the file to disk.
  try {
    await retryDeliveryRequest(() => completeDownloadJob(job.id, received));
  } catch (cause) {
    // The file is already committed; purging the job is also a successful cleanup.
    if (!(cause instanceof ApiRequestError && cause.status === 404)) throw cause;
  }
}

export function isDestinationCancelled(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
