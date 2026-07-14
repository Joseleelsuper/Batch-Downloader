import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App, { ScraperQueues } from './App';
import * as catalogApi from './api/catalog';
import type { ScraperQueueState } from './types/catalog';

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, String(value)),
  };
}

describe('catalog workspace', () => {
  beforeEach(() => {
    const storage = memoryStorage();
    Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
    vi.stubGlobal('localStorage', storage);
    vi.spyOn(catalogApi, 'me').mockRejectedValue(new Error('anonymous'));
    vi.spyOn(catalogApi, 'connectCatalogEvents').mockReturnValue(() => undefined);
    vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
    });
    vi.spyOn(catalogApi, 'fetchCatalogStats').mockResolvedValue({
      total: 0,
      filters: { all: 0, available: 0, review: 0, missing: 0 },
      lastScrape: null,
      generatedAt: '2026-07-13T12:00:00Z',
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('keeps every desktop grid region in place while the filter rail is hidden', async () => {
    window.localStorage.setItem('catalog.filters.open', 'false');
    const { container } = render(
      <MemoryRouter initialEntries={['/catalog']}>
        <App />
      </MemoryRouter>,
    );

    const workspace = container.querySelector('main.workspace');
    expect(workspace).toHaveClass('filters-hidden');
    expect(workspace?.children[0]).toHaveClass('filter-rail-shell');
    expect(workspace?.children[0]).toHaveAttribute('hidden');
    expect(workspace?.children[1]).toHaveClass('filter-bookmark');
    expect(workspace?.children[2]).toHaveClass('catalog-panel');
    expect(workspace?.children[3]).toHaveClass('details-drawer');

    const bookmark = screen.getByRole('button', { name: 'Abrir filtros' });
    expect(bookmark).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(bookmark);

    expect(workspace).not.toHaveClass('filters-hidden');
    expect(workspace?.children[0]).not.toHaveAttribute('hidden');
    expect(window.localStorage.getItem('catalog.filters.open')).toBe('true');
    await waitFor(() => expect(catalogApi.fetchApps).toHaveBeenCalled());
  });
});

function queue(queueName: string, appName: string): ScraperQueueState {
  return {
    queue: queueName,
    queued: 1,
    inProgress: 0,
    completed: 0,
    discarded: 0,
    failed: 0,
    items: [{
      id: `${queueName}-1`,
      packageId: `${queueName}.package`,
      appName,
      status: 'queued',
      attempts: 0,
      updatedAt: '2026-07-13T12:00:00Z',
    }],
  };
}

describe('scraper pipeline', () => {
  afterEach(() => cleanup());

  it('presents SO Filter and ignores the removed icon enrichment queue', () => {
    render(<ScraperQueues queues={[
      queue('scraper_so_filter', 'Pendiente de clasificar'),
      queue('so_filter_descriptor', 'Pendiente de descripción'),
      queue('icon_enrichment', 'Trabajo de iconos antiguo'),
    ]} />);

    expect(screen.getByText('Scraper -> SO Filter')).toBeInTheDocument();
    expect(screen.getByText('SO Filter')).toBeInTheDocument();
    expect(screen.getByText('SO Filter -> Descriptor')).toBeInTheDocument();
    expect(screen.getByText('Pendiente de clasificar')).toBeInTheDocument();
    expect(screen.queryByText('Trabajo de iconos antiguo')).not.toBeInTheDocument();
    expect(screen.queryByText('Iconos')).not.toBeInTheDocument();
  });

  it('uses the legacy descriptor queue until the SO Filter migration is deployed', () => {
    render(<ScraperQueues queues={[queue('scraper_descriptor', 'Trabajo compatible')]} />);

    expect(screen.getByText('Trabajo compatible')).toBeInTheDocument();
    expect(screen.getByText('SO Filter -> Descriptor')).toBeInTheDocument();
  });
});
