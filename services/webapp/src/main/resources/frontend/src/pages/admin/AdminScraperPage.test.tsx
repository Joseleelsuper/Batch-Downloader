import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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
  fetchAdminCurrentRun: vi.fn(),
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
    vi.mocked(scraperAdminApi.fetchAdminCurrentRun).mockResolvedValue(latestRun);
    vi.mocked(scraperAdminApi.fetchAdminRuns).mockResolvedValue([latestRun]);
    vi.mocked(scraperAdminApi.fetchAdminLogs).mockResolvedValue([]);
    vi.mocked(scraperAdminApi.fetchAdminQueues).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('muestra las fechas de la última ejecución', async () => {
    const { container } = render(<MemoryRouter><AdminScraperPage /></MemoryRouter>);

    const status = container.querySelector('.scraper-status');
    await waitFor(() => expect(status).toHaveTextContent('completed'));
    expect(within(status as HTMLElement).getByText('completed')).toBeInTheDocument();
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

    await waitFor(() => expect(container.querySelector('.scraper-status')).toHaveTextContent('running'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
