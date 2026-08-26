import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DownloadJob } from '../types/catalog';
import { useDownloadJob } from './useDownloadJob';

const mocks = vi.hoisted(() => ({ useDownloadJobs: vi.fn() }));
vi.mock('../downloads/DownloadJobsContext', () => ({
  TERMINAL_DOWNLOAD_STATUSES: new Set([
    'READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED',
  ]),
  useDownloadJobs: mocks.useDownloadJobs,
}));

function job(status: DownloadJob['status']): DownloadJob {
  return {
    id: 'job-1', status, failureCode: null, progress: 0,
    requestedCount: 1, acceptedCount: 1, omittedCount: 0, items: [],
    createdAt: '2026-08-24T10:00:00Z', expiresAt: '2026-08-25T10:00:00Z',
  };
}

function context() {
  return {
    jobs: [] as Array<{
      id: string;
      job: DownloadJob | null;
      cancelling: boolean;
      connectionError: boolean;
      actionError: string | null;
    }>,
    start: vi.fn(),
    cancel: vi.fn(),
    dismiss: vi.fn(),
  };
}

describe('useDownloadJob', () => {
  beforeEach(() => mocks.useDownloadJobs.mockReset());
  afterEach(cleanup);

  it('deduplica un inicio concurrente y permite limpiar un terminal', async () => {
    const downloads = context();
    let resolveStart: ((value: DownloadJob) => void) | undefined;
    const operation = new Promise<DownloadJob>((resolve) => { resolveStart = resolve; });
    downloads.start.mockReturnValue(operation);
    mocks.useDownloadJobs.mockReturnValue(downloads);
    const { result } = renderHook(() => useDownloadJob());

    let first!: Promise<DownloadJob>;
    let second!: Promise<DownloadJob>;
    act(() => {
      first = result.current.start({ appIds: ['app-1'] });
      second = result.current.start({ appIds: ['app-1'] });
    });
    expect(downloads.start).toHaveBeenCalledOnce();
    const ready = job('READY');
    downloads.jobs = [{
      id: ready.id, job: ready, cancelling: false, connectionError: false, actionError: null,
    }];
    await act(async () => {
      resolveStart?.(ready);
      await Promise.all([first, second]);
    });

    expect(result.current.job).toEqual(ready);
    act(() => result.current.clear());
    expect(downloads.dismiss).toHaveBeenCalledWith('job-1');
    expect(result.current.job).toBeNull();
  });

  it('expone fallos de inicio y los reinicia antes del siguiente intento', async () => {
    const downloads = context();
    downloads.start
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(job('QUEUED'));
    mocks.useDownloadJobs.mockReturnValue(downloads);
    const { result } = renderHook(() => useDownloadJob());

    await act(async () => {
      await expect(result.current.start({ appIds: ['app-1'] })).rejects.toThrow('offline');
    });
    expect(result.current.error).toBe(true);
    await act(async () => {
      await result.current.start({ appIds: ['app-1'] });
    });
    expect(result.current.error).toBe(false);
    expect(result.current.starting).toBe(false);
  });

  it('cancela solo trabajos activos y propaga un fallo de acción', async () => {
    const downloads = context();
    downloads.start.mockResolvedValue(job('DOWNLOADING'));
    downloads.cancel.mockResolvedValue(undefined);
    mocks.useDownloadJobs.mockReturnValue(downloads);
    const { result, rerender } = renderHook(() => useDownloadJob());

    await act(async () => result.current.cancel());
    expect(downloads.cancel).not.toHaveBeenCalled();
    await act(async () => {
      await result.current.start({ appIds: ['app-1'] });
    });
    downloads.jobs = [{
      id: 'job-1', job: job('DOWNLOADING'), cancelling: true,
      connectionError: false, actionError: null,
    }];
    rerender();
    expect(result.current.cancelling).toBe(true);
    await act(async () => result.current.cancel());
    expect(downloads.cancel).toHaveBeenCalledWith('job-1');

    downloads.cancel.mockRejectedValueOnce(new Error('cancel failed'));
    await act(async () => {
      await expect(result.current.cancel()).rejects.toThrow('cancel failed');
    });
    expect(result.current.error).toBe(true);
  });

  it('combina errores de conexión y acción y no descarta un trabajo activo', async () => {
    const downloads = context();
    downloads.start.mockResolvedValue(job('QUEUED'));
    mocks.useDownloadJobs.mockReturnValue(downloads);
    const { result, rerender } = renderHook(() => useDownloadJob());
    await act(async () => {
      await result.current.start({ appIds: ['app-1'] });
    });
    downloads.jobs = [{
      id: 'job-1', job: job('QUEUED'), cancelling: false,
      connectionError: true, actionError: 'failed',
    }];
    rerender();

    expect(result.current.error).toBe(true);
    act(() => result.current.clear());
    expect(downloads.dismiss).not.toHaveBeenCalled();
  });
});
