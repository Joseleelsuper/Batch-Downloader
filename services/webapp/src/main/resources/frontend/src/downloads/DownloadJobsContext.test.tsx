import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as downloadsApi from '../api/downloads';
import { ApiRequestError } from '../api/http';
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

function ActionsHarness() {
  const downloads = useDownloadJobs();
  const first = downloads.jobs[0];
  return (
    <div>
      <button
        type="button"
        onClick={() => void downloads.start({ appIds: ['app-1'] }).catch(() => undefined)}
      >
        Iniciar sin etiqueta
      </button>
      <button
        type="button"
        onClick={() => void downloads.start({ appIds: ['app-2'] }, '  Etiqueta  ')
          .catch(() => undefined)}
      >
        Iniciar etiquetado
      </button>
      <button
        type="button"
        onClick={() => void downloads.cancel(first?.id ?? 'missing').catch(() => undefined)}
      >
        Cancelar
      </button>
      <button type="button" onClick={() => downloads.dismiss(first?.id ?? 'missing')}>
        Descartar
      </button>
      <button type="button" onClick={() => downloads.toggleMinimized(first?.id ?? 'missing')}>
        Minimizar
      </button>
      <button type="button" onClick={downloads.clearStartError}>Limpiar error</button>
      <output data-testid="state">{JSON.stringify({ jobs: downloads.jobs, error: downloads.startError })}</output>
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
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    const connect = vi.spyOn(downloadsApi, 'connectDownloadJobEvents')
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

  it('restaura únicamente entradas válidas y tolera almacenamiento corrupto', () => {
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(vi.fn());
    window.sessionStorage.setItem('batch-downloader.download-jobs.v1', 'not-json');
    const corrupt = render(<DownloadJobsProvider><Harness /></DownloadJobsProvider>);
    expect(screen.getByTestId('jobs')).toHaveTextContent('0');
    corrupt.unmount();

    window.sessionStorage.setItem('batch-downloader.download-jobs.v1', JSON.stringify({ id: 'no-array' }));
    const object = render(<DownloadJobsProvider><Harness /></DownloadJobsProvider>);
    expect(screen.getByTestId('jobs')).toHaveTextContent('0');
    object.unmount();

    window.sessionStorage.setItem('batch-downloader.download-jobs.v1', JSON.stringify([
      null,
      {},
      { id: 3, label: 'number-id' },
      { id: 'missing-label' },
      { id: 'valid', label: 'Restaurada', autoDownloadAttempted: false, minimized: true },
    ]));
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);
    expect(screen.getByTestId('state')).toHaveTextContent('Restaurada');
    expect(screen.getByTestId('state')).toHaveTextContent('"minimized":true');
  });

  it('continúa en memoria si sessionStorage rechaza la persistencia', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError');
    });
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(vi.fn());
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);

    fireEvent.click(screen.getByRole('button', { name: 'Iniciar sin etiqueta' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent(JOB_ID));
  });

  it('aplica la etiqueta por defecto, reemplaza copias obsoletas y minimiza', async () => {
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(vi.fn());
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);

    fireEvent.click(screen.getByRole('button', { name: 'Iniciar sin etiqueta' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent(JOB_ID));
    expect(screen.getByTestId('state')).toHaveTextContent('Trabajo de descarga');
    fireEvent.click(screen.getByRole('button', { name: 'Iniciar etiquetado' }));
    await waitFor(() => expect(downloadsApi.createDownloadJob).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('state')).toHaveTextContent('Etiqueta');
    const state = JSON.parse(screen.getByTestId('state').textContent ?? '{}') as {
      jobs?: unknown[];
    };
    expect(state.jobs).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Minimizar' }));
    expect(screen.getByTestId('state')).toHaveTextContent('"minimized":true');
  });

  it('traduce errores conocidos, desconocidos y de transporte al iniciar', async () => {
    vi.spyOn(downloadsApi, 'createDownloadJob')
      .mockRejectedValueOnce(new ApiRequestError(400, 'invalid_job_size'))
      .mockRejectedValueOnce(new ApiRequestError(400, 'unknown_code'))
      .mockRejectedValueOnce(new TypeError('offline'));
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);
    const start = screen.getByRole('button', { name: 'Iniciar sin etiqueta' });

    fireEvent.click(start);
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('entre 1 y 100'));
    fireEvent.click(screen.getByRole('button', { name: 'Limpiar error' }));
    expect(screen.getByTestId('state')).toHaveTextContent('"error":null');

    fireEvent.click(start);
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent(
      'No se pudo iniciar el trabajo de descarga',
    ));
    fireEvent.click(start);
    await waitFor(() => expect(downloadsApi.createDownloadJob).toHaveBeenCalledTimes(3));
    expect(screen.getByTestId('state')).toHaveTextContent('No se pudo iniciar el trabajo de descarga');
  });

  it('cancela, evita repetir acciones terminales y permite descartar', async () => {
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    const cancel = vi.spyOn(downloadsApi, 'cancelDownloadJob').mockResolvedValue(job('CANCELLED', 0));
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(vi.fn());
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Iniciar sin etiqueta' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('QUEUED'));
    fireEvent.click(screen.getByRole('button', { name: 'Descartar' }));
    expect(screen.getByTestId('state')).toHaveTextContent(JOB_ID);
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('CANCELLED'));
    expect(screen.getByTestId('state')).toHaveTextContent('"cancelling":false');
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(cancel).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByRole('button', { name: 'Descartar' }));
    expect(screen.getByTestId('state')).not.toHaveTextContent(JOB_ID);
  });

  it('expone el error de cancelación y recupera el estado cancelling', async () => {
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    vi.spyOn(downloadsApi, 'cancelDownloadJob').mockRejectedValue(
      new ApiRequestError(409, 'invalid_job_size'),
    );
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockReturnValue(vi.fn());
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);

    fireEvent.click(screen.getByRole('button', { name: 'Iniciar sin etiqueta' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('QUEUED'));
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('entre 1 y 100'));
    expect(screen.getByTestId('state')).toHaveTextContent('"cancelling":false');
  });

  it('marca errores de conexión y elimina trabajos no autorizados o ausentes', async () => {
    let reportError: ((cause?: unknown) => void) | undefined;
    vi.spyOn(downloadsApi, 'createDownloadJob').mockResolvedValue(job('QUEUED', 0));
    vi.spyOn(downloadsApi, 'connectDownloadJobEvents').mockImplementation((_id, _onJob, onError) => {
      reportError = onError;
      return vi.fn();
    });
    render(<DownloadJobsProvider><ActionsHarness /></DownloadJobsProvider>);
    fireEvent.click(screen.getByRole('button', { name: 'Iniciar sin etiqueta' }));
    await waitFor(() => expect(reportError).toBeDefined());

    act(() => reportError?.(new TypeError('offline')));
    expect(screen.getByTestId('state')).toHaveTextContent('"connectionError":true');
    act(() => reportError?.(new ApiRequestError(404, 'not_found')));
    expect(screen.getByTestId('state')).not.toHaveTextContent(JOB_ID);
  });

});
