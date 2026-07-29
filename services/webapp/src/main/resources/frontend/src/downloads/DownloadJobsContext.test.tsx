import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as catalogApi from '../api/catalog';
import type { DownloadJob } from '../types/catalog';
import {
  DownloadJobsProvider,
  useDownloadJobs,
} from './DownloadJobsContext';

const JOB_ID = 'a51d185b-1966-4c51-83e2-9b2f523f47ce';

function job(status: DownloadJob['status'], progress: number): DownloadJob {
  return {
    id: JOB_ID,
    status,
    failureCode: null,
    progress,
    requestedCount: 1,
    acceptedCount: 1,
    omittedCount: 0,
    items: [{
      id: '143089b4-8494-4fa7-a365-47dc882c6b72',
      appId: '53184a90-ab4b-4609-bbce-456f913fe691',
      appName: 'Aplicación de ejemplo',
      status: status === 'READY' ? 'COMPLETED' : 'DOWNLOADING',
      bytesDownloaded: status === 'READY' ? 1024 : 0,
    }],
    createdAt: '2026-07-11T08:00:00Z',
    expiresAt: '2026-07-12T08:00:00Z',
  };
}

function Harness() {
  const downloads = useDownloadJobs();
  return (
    <div>
      <button
        type="button"
        onClick={() => void downloads.start(
          { appIds: ['53184a90-ab4b-4609-bbce-456f913fe691'] },
          'Aplicación de ejemplo',
        )}
      >
        Iniciar
      </button>
      <output data-testid="jobs">{downloads.jobs.length}</output>
      <output data-testid="status">{downloads.jobs[0]?.job?.status ?? 'restoring'}</output>
    </div>
  );
}

describe('DownloadJobsProvider', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('keeps one subscription per job and attempts the automatic download exactly once', async () => {
    let pushJob: ((value: DownloadJob) => void) | undefined;
    const disconnect = vi.fn();
    vi.spyOn(catalogApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    const connect = vi.spyOn(catalogApi, 'connectDownloadJobEvents')
      .mockImplementation((_jobId, onJob) => {
        pushJob = onJob;
        return disconnect;
      });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    const first = render(
      <DownloadJobsProvider>
        <Harness />
      </DownloadJobsProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Iniciar' }));

    await waitFor(() => expect(connect).toHaveBeenCalledOnce());
    act(() => pushJob?.(job('DOWNLOADING', 45)));
    act(() => pushJob?.(job('PACKAGING', 90)));
    expect(connect).toHaveBeenCalledOnce();

    act(() => pushJob?.(job('READY', 100)));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(disconnect).toHaveBeenCalledOnce();
    expect(window.sessionStorage.getItem('batch-downloader.download-jobs.v1'))
      .toContain('"autoDownloadAttempted":true');

    first.unmount();
    pushJob = undefined;
    render(
      <DownloadJobsProvider>
        <Harness />
      </DownloadJobsProvider>,
    );

    await waitFor(() => expect(connect).toHaveBeenCalledTimes(2));
    act(() => pushJob?.(job('READY', 100)));
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('READY'));
    expect(click).toHaveBeenCalledOnce();
  });
});
