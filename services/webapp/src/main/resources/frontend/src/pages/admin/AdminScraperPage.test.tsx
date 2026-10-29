import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as scraperAdminApi from '../../api/scraperAdmin';
import type { ScraperRunSummary } from '../../types/catalog';
import { formatDate } from '../../utils/date';
import { AdminScraperPage } from './AdminScraperPage';

vi.mock('../../api/scraperAdmin', () => ({
  connectScraperEvents: vi.fn(() => () => undefined),
  createScraperRun: vi.fn(),
  enqueueMissingScraperDescriptions: vi.fn(),
  regenerateScraperDescriptions: vi.fn(),
  fetchAdminCurrentRun: vi.fn(),
  fetchAdminFailedItems: vi.fn(),
  fetchAdminLogs: vi.fn(),
  fetchAdminQueues: vi.fn(),
  fetchAdminRuns: vi.fn(),
  pruneTerminalScraperQueueItems: vi.fn(),
  recoverStuckScraperQueueItems: vi.fn(),
  retryFailedScraperQueueItems: vi.fn(),
  sendScraperCommand: vi.fn(),
}));

const latestRun: ScraperRunSummary = {
  id: 'run-1',
  status: 'completed',
  scope: 'incremental',
  targetCount: 2,
  startedAt: '2026-09-20T01:00:00',
  heartbeatAt: '2026-09-20T01:30:00',
  finishedAt: '2026-09-20T01:31:00',
  appsDiscovered: 2,
  appsResolved: 2,
  appsFailed: 0,
  appsSkipped: 0,
  appsConfirmedMissing: 0,
  appsNeedsReview: 0,
  appsTransientFailed: 0,
  appsSkippedUnchanged: 0,
  stopRequested: false,
};

describe('AdminScraperPage', () => {
  beforeEach(() => {
    vi.mocked(scraperAdminApi.regenerateScraperDescriptions).mockReset();
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(latestRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([latestRun]);
    vi.mocked(scraperAdminApi.fetchAdminLogs).mockResolvedValue([]);
    vi.mocked(scraperAdminApi.fetchAdminFailedItems).mockResolvedValue([]);
    vi.mocked(scraperAdminApi.fetchAdminQueues).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('muestra las fechas de la última ejecución', async () => {
    const { container } = render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    const status = container.querySelector('.scraper-status');
    await waitFor(() => expect(status).toHaveTextContent('Completada'));
    expect(within(status as HTMLElement).getByText('Completada')).toBeInTheDocument();
    expect(status).toHaveTextContent('Inicio');
    expect(status).toHaveTextContent(formatDate(`${latestRun.startedAt}Z`));
    expect(status).toHaveTextContent('Fin');
    expect(status).toHaveTextContent(formatDate(`${latestRun.finishedAt}Z`));
    expect(status).toHaveTextContent('Último latido');
    expect(status).toHaveTextContent(formatDate(`${latestRun.heartbeatAt}Z`));
  });

  it('avisa si una ejecución activa no renueva el latido en 90 minutos', async () => {
    const staleRun = {
      ...latestRun,
      status: 'running',
      finishedAt: null,
      heartbeatAt: '2000-01-01T00:00:00',
    } satisfies ScraperRunSummary;
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(staleRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([staleRun]);

    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'El latido lleva más de 90 minutos sin actualizarse',
    );
  });

  it('no marca obsoleto un latido reciente', async () => {
    const recentRun = {
      ...latestRun,
      status: 'running',
      finishedAt: null,
      heartbeatAt: new Date(Date.now() - 10_000).toISOString(),
    } satisfies ScraperRunSummary;
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(recentRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([recentRun]);

    const { container } = render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    await waitFor(() => expect(container.querySelector('.scraper-status')).toHaveTextContent('En curso'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explica el fallo y permite ver incidencias antiguas con sus diagnósticos', async () => {
    const failedRun = {
      ...latestRun,
      status: 'failed',
      currentPhase: 'failed',
      errorSummary: 'OperationalError (MySQL 1038): MySQL ran out of sort memory',
    } satisfies ScraperRunSummary;
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(failedRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([failedRun]);
    vi.mocked(scraperAdminApi.fetchAdminFailedItems).mockResolvedValue([{
      id: 'item-1', queue: 'filter_scraper', packageId: 'Vendor.App', appName: 'Vendor App',
      attempts: 4, lastError: 'TimeoutError: validation timed out', updatedAt: latestRun.startedAt,
    }]);
    vi.mocked(scraperAdminApi.fetchAdminLogs).mockResolvedValue([{
      id: 'log-1', phase: 'resolve', status: 'rejected',
      message: 'No safe installer candidate was found.',
      safeMetadata: JSON.stringify({
        winstall_id: 'Vendor.App',
        candidate_diagnostics: {
          direct: { discovered: 3, eligible: 2, attempted: 2, valid: 0,
            rejected: { invalid_content_type: 2 } },
        },
      }),
      createdAt: latestRun.startedAt,
    }]);

    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    expect(await screen.findAllByText(/La base de datos agotó la memoria/)).toHaveLength(2);
    expect(screen.getByText('TimeoutError: validation timed out')).toBeInTheDocument();
    expect(screen.getByText('Vendor App')).toBeInTheDocument();
    await waitFor(() => expect(scraperAdminApi.fetchAdminLogs).toHaveBeenCalledWith(true));
    fireEvent.click(screen.getByText('No se encontró ningún candidato de instalador seguro.'));
    expect(screen.getByText(/invalid content type \(2\)/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Toda la actividad' }));
    await waitFor(() => expect(scraperAdminApi.fetchAdminLogs).toHaveBeenCalledWith(false));
  });

  it('explica una ejecución parcial y el agotamiento del pool de base de datos', async () => {
    const partialRun = {
      ...latestRun,
      status: 'partial',
      appsTransientFailed: 2,
      appsFailed: 2,
    } satisfies ScraperRunSummary;
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(partialRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([partialRun]);
    vi.mocked(scraperAdminApi.fetchAdminFailedItems).mockResolvedValue([{
      id: 'item-2', queue: 'filter_scraper', packageId: 'Vendor.App',
      attempts: 4, lastError: 'database_pool_timeout', updatedAt: latestRun.startedAt,
    }]);

    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    expect(await screen.findAllByText(/2 aplicaciones tuvieron fallos temporales/)).toHaveLength(2);
    expect(screen.getByText('El pool de conexiones de la base de datos agotó el tiempo de espera.'))
      .toBeInTheDocument();
  });

  it('encola todas las páginas y muestra el total acumulado', async () => {
    const enqueue = vi.mocked(scraperAdminApi.enqueueMissingScraperDescriptions);
    enqueue.mockResolvedValueOnce({
      matched: 2, enqueued: 1, alreadyActive: 1,
      nextAfter: '00000000-0000-0000-0000-000000000002',
    }).mockResolvedValueOnce({
      matched: 1, enqueued: 1, alreadyActive: 0, nextAfter: null,
    });

    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Encolar sin descripción' }));

    await waitFor(() => expect(enqueue).toHaveBeenCalledTimes(2));
    expect(enqueue).toHaveBeenNthCalledWith(1, undefined);
    expect(enqueue).toHaveBeenNthCalledWith(2, '00000000-0000-0000-0000-000000000002');
    expect(await screen.findByText('Trabajos encolados: 2; ya activos: 1.')).toBeInTheDocument();
  });

  it.each([
    ['ai', 'Regenerar descripciones de IA'],
    ['manual', 'Regenerar descripciones manuales'],
    ['all', 'Regenerar todas las descripciones'],
  ] as const)('regenera el ámbito %s y recorre todos los lotes', async (scope, label) => {
    const regenerate = vi.mocked(scraperAdminApi.regenerateScraperDescriptions);
    regenerate.mockResolvedValueOnce({
      matched: 2, enqueued: 1, alreadyActive: 1, nextAfter: 'cursor',
    }).mockResolvedValueOnce({
      matched: 1, enqueued: 1, alreadyActive: 0, nextAfter: null,
    });
    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: label }));
    await waitFor(() => expect(regenerate).toHaveBeenCalledTimes(2));
    expect(regenerate).toHaveBeenNthCalledWith(1, scope, undefined);
    expect(regenerate).toHaveBeenNthCalledWith(2, scope, 'cursor');
    expect(await screen.findByText('Trabajos encolados: 2; ya activos: 1.')).toBeInTheDocument();
  });

  it('conserva los contadores si falla un lote posterior', async () => {
    vi.mocked(scraperAdminApi.regenerateScraperDescriptions).mockResolvedValueOnce({
      matched: 2, enqueued: 1, alreadyActive: 1, nextAfter: 'cursor',
    }).mockRejectedValueOnce(new Error('unavailable'));
    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Regenerar todas las descripciones' }));
    expect(await screen.findByText(
      'No se pudo encolar el enriquecimiento. Trabajos encolados: 1; ya activos: 1.',
    )).toBeInTheDocument();
  });

  it('bloquea las cuatro acciones mientras encola y muestra un ámbito vacío', async () => {
    let finish!: (result: Awaited<ReturnType<typeof scraperAdminApi.regenerateScraperDescriptions>>) => void;
    vi.mocked(scraperAdminApi.regenerateScraperDescriptions).mockImplementationOnce(
      () => new Promise((resolve) => { finish = resolve; }),
    );
    render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);
    const button = screen.getByRole('button', { name: 'Regenerar descripciones manuales' });
    fireEvent.click(button);
    for (const label of ['Encolar sin descripción', 'Regenerar descripciones de IA',
      'Regenerar descripciones manuales', 'Regenerar todas las descripciones']) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled();
    }
    finish({ matched: 0, enqueued: 0, alreadyActive: 0, nextAfter: null });
    expect(await screen.findByText('No hay descripciones que coincidan con el ámbito elegido.'))
      .toBeInTheDocument();
    expect(button).toBeEnabled();
  });

});
