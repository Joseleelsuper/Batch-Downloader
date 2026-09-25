import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as downloads from '../api/downloads';
import * as http from '../api/http';
import { ApiRequestError } from '../api/http';
import type { DownloadJob } from '../types/catalog';
import { chooseDownloadDestination, saveDownload } from './delivery';

const job = { id: 'job/id', artifactSizeBytes: 4 } as DownloadJob;

function target() {
  const writer = {
    write: vi.fn().mockResolvedValue(undefined),
    close: vi.fn().mockResolvedValue(undefined),
    abort: vi.fn().mockResolvedValue(undefined),
  };
  return { writer, handle: { createWritable: vi.fn().mockResolvedValue(writer) } };
}

beforeEach(() => {
  vi.spyOn(downloads, 'reportDownloadActivity').mockResolvedValue(undefined);
  vi.spyOn(downloads, 'completeDownloadJob').mockResolvedValue(undefined);
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('managed ZIP delivery', () => {
  it('recovers reconciliation and transient not-ready errors without reopening the destination', async () => {
    vi.useFakeTimers();
    const { handle, writer } = target();
    vi.mocked(downloads.reportDownloadActivity)
      .mockRejectedValueOnce(new ApiRequestError(503, 'storage_reconciling', '2'));
    const fetch = vi.spyOn(http, 'apiFetch')
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'download_not_ready' }), { status: 409 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'storage_reconciling' }), { status: 503 }))
      .mockResolvedValueOnce(new Response(new Uint8Array(4)));
    const operation = saveDownload(job, handle, vi.fn());
    await vi.advanceTimersByTimeAsync(10_000);
    await operation;
    expect(handle.createWritable).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(writer.write).toHaveBeenCalledOnce();
    expect(writer.close).toHaveBeenCalledOnce();
    expect(writer.abort).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('bounds repeated service-unavailable responses to four attempts', async () => {
    vi.useFakeTimers();
    const { handle, writer } = target();
    const fetch = vi.spyOn(http, 'apiFetch').mockImplementation(async () => new Response(
      JSON.stringify({ code: 'storage_reconciling' }), { status: 503, headers: { 'Retry-After': '600' } },
    ));
    const failure = saveDownload(job, handle, vi.fn()).catch((cause: unknown) => cause);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(await failure).toMatchObject({ status: 503, code: 'storage_reconciling' });
    expect(fetch).toHaveBeenCalledTimes(4);
    expect(writer.abort).toHaveBeenCalledOnce();
    expect(writer.close).not.toHaveBeenCalled();
    expect(downloads.completeDownloadJob).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('retries an ambiguous completion response without downloading or writing the ZIP again', async () => {
    vi.useFakeTimers();
    const { handle, writer } = target();
    const fetch = vi.spyOn(http, 'apiFetch').mockResolvedValue(new Response(new Uint8Array(4)));
    const saved = vi.fn();
    vi.mocked(downloads.completeDownloadJob)
      .mockImplementationOnce(async () => {
        expect(saved).toHaveBeenCalledOnce();
        throw new DOMException('response timed out', 'AbortError');
      });
    const operation = saveDownload(job, handle, vi.fn(), saved);
    await vi.advanceTimersByTimeAsync(2000);
    await operation;
    expect(fetch).toHaveBeenCalledOnce();
    expect(writer.write).toHaveBeenCalledOnce();
    expect(writer.close).toHaveBeenCalledOnce();
    expect(vi.mocked(downloads.completeDownloadJob).mock.calls).toEqual([[job.id, 4], [job.id, 4]]);
    expect(writer.abort).not.toHaveBeenCalled();
  });

  it('accepts a missing job after the file was saved and its completion was retried', async () => {
    const { handle, writer } = target();
    vi.spyOn(http, 'apiFetch').mockResolvedValue(new Response(new Uint8Array(4)));
    vi.mocked(downloads.completeDownloadJob).mockRejectedValue(new ApiRequestError(404, 'download_job_not_found'));
    await expect(saveDownload(job, handle, vi.fn())).resolves.toBeUndefined();
    expect(writer.close).toHaveBeenCalledOnce();
    expect(writer.abort).not.toHaveBeenCalled();
  });

  it('preserves the committed file when the receipt remains unavailable', async () => {
    vi.useFakeTimers();
    const { handle, writer } = target();
    vi.spyOn(http, 'apiFetch').mockResolvedValue(new Response(new Uint8Array(4)));
    vi.mocked(downloads.completeDownloadJob).mockRejectedValue(new TypeError('offline'));
    const saved = vi.fn();
    const failure = saveDownload(job, handle, vi.fn(), saved).catch((cause: unknown) => cause);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(await failure).toBeInstanceOf(TypeError);
    expect(saved).toHaveBeenCalledOnce();
    expect(writer.close).toHaveBeenCalledOnce();
    expect(writer.abort).not.toHaveBeenCalled();
    expect(downloads.completeDownloadJob).toHaveBeenCalledTimes(4);
  });

  it('opens the native destination picker, or uses normal downloads where unavailable', async () => {
    await expect(chooseDownloadDestination()).resolves.toBeUndefined();
    const handle = target().handle;
    const picker = vi.fn().mockResolvedValue(handle);
    vi.stubGlobal('showSaveFilePicker', picker);
    const destination = chooseDownloadDestination();
    expect(picker).toHaveBeenCalledOnce();
    await expect(destination).resolves.toBe(handle);
  });

  it('writes chunks with backpressure and confirms only after close finishes', async () => {
    const { handle, writer } = target();
    const events: string[] = [];
    writer.write.mockImplementation(async (bytes: Uint8Array) => { events.push(`write:${bytes.length}`); });
    let close!: () => void;
    writer.close.mockImplementation(() => new Promise<void>((resolve) => { close = resolve; }));
    vi.spyOn(http, 'apiFetch').mockResolvedValue(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new Uint8Array([1, 2]));
        controller.enqueue(new Uint8Array([3, 4]));
        controller.close();
      },
    })));
    const onBytes = vi.fn();
    const operation = saveDownload(job, handle, onBytes);
    await vi.waitFor(() => expect(writer.close).toHaveBeenCalledOnce());
    expect(events).toEqual(['write:2', 'write:2']);
    expect(onBytes.mock.calls).toEqual([[2], [4]]);
    expect(downloads.completeDownloadJob).not.toHaveBeenCalled();
    close();
    await operation;
    expect(downloads.completeDownloadJob).toHaveBeenCalledWith(job.id, 4);
    expect(writer.abort).not.toHaveBeenCalled();
  });

  it('resumes a truncated response using Range without rewriting received bytes', async () => {
    const { handle, writer } = target();
    const fetch = vi.spyOn(http, 'apiFetch')
      .mockResolvedValueOnce(new Response(new Uint8Array([1, 2])))
      .mockResolvedValueOnce(new Response(new Uint8Array([3, 4]), {
        status: 206, headers: { 'Content-Range': 'bytes 2-3/4' },
      }));
    await saveDownload(job, handle, vi.fn());
    expect(fetch.mock.calls[1]).toEqual([
      '/api/v1/download-jobs/job%2Fid/file', { headers: { Range: 'bytes=2-' }, timeoutMs: 10_000 },
    ]);
    expect(writer.write.mock.calls.map(([bytes]) => [...bytes as Uint8Array])).toEqual([[1, 2], [3, 4]]);
    expect(downloads.completeDownloadJob).toHaveBeenCalledWith(job.id, 4);
  });

  it.each(['oversize', 'range', 'disk'])('aborts %s failures without confirming a save', async (failure) => {
    const { handle, writer } = target();
    const fetch = vi.spyOn(http, 'apiFetch');
    if (failure === 'oversize') fetch.mockResolvedValue(new Response(new Uint8Array(5)));
    if (failure === 'range') fetch
      .mockResolvedValueOnce(new Response(new Uint8Array(2)))
      .mockResolvedValueOnce(new Response(new Uint8Array(4)));
    if (failure === 'disk') {
      fetch.mockResolvedValue(new Response(new Uint8Array(4)));
      writer.write.mockRejectedValue(new DOMException('disk full', 'QuotaExceededError'));
    }
    await expect(saveDownload(job, handle, vi.fn())).rejects.toThrow();
    expect(writer.abort).toHaveBeenCalledOnce();
    expect(writer.close).not.toHaveBeenCalled();
    expect(downloads.completeDownloadJob).not.toHaveBeenCalled();
  });

  it('reports actual written bytes while a connected transfer is stalled', async () => {
    vi.useFakeTimers();
    const { handle } = target();
    let source!: ReadableStreamDefaultController<Uint8Array>;
    vi.spyOn(http, 'apiFetch').mockResolvedValue(new Response(new ReadableStream({
      start(controller) { source = controller; controller.enqueue(new Uint8Array(2)); },
    })));
    const operation = saveDownload(job, handle, vi.fn());
    await vi.advanceTimersByTimeAsync(15_000);
    expect(downloads.reportDownloadActivity).toHaveBeenLastCalledWith(job.id, 'saving', 2);
    source.enqueue(new Uint8Array(2));
    source.close();
    await operation;
    expect(vi.getTimerCount()).toBe(0);
  });
});
