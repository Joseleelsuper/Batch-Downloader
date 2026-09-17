import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as semanticApi from '../../api/semanticAdmin';
import { SemanticAiPage } from './SemanticAiPage';
import type { SemanticOverview } from '../../types/semanticAdmin';

vi.mock('../../api/semanticAdmin', () => ({
  fetchSemanticOverview: vi.fn(),
}));

const healthyOverview: SemanticOverview = {
  service: 'semantic-service',
  status: 'ok',
  database: true,
  searchReady: true,
  model: { version: 'local-model-v1', dimensions: 768, artifactReady: true },
  index: {
    indexVersion: 'index-v1',
    expected: 10,
    indexed: 10,
    complete: true,
    builtAt: '2026-09-15T10:00:00Z',
  },
  indexer: { present: true, healthy: true, reason: 'ok', consecutiveFailures: 0 },
};

describe('SemanticAiPage', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.mocked(semanticApi.fetchSemanticOverview).mockReset();
  });

  it('muestra el modelo, la cobertura y la disponibilidad semántica', async () => {
    vi.mocked(semanticApi.fetchSemanticOverview).mockResolvedValue(healthyOverview);

    render(<MemoryRouter><SemanticAiPage /></MemoryRouter>);

    expect(await screen.findByText('local-model-v1')).toBeInTheDocument();
    expect(screen.getByText(/10 \/ 10/)).toBeInTheDocument();
    expect(screen.getByText('Búsqueda semántica disponible')).toBeInTheDocument();
    expect(screen.getByText('Saludable')).toBeInTheDocument();
  });

  it('permite actualizar el estado y representa una degradación', async () => {
    vi.mocked(semanticApi.fetchSemanticOverview)
      .mockResolvedValueOnce(healthyOverview)
      .mockResolvedValueOnce({
        ...healthyOverview,
        status: 'degraded',
        searchReady: false,
        index: { ...healthyOverview.index, indexed: 4, complete: false },
      });

    render(<MemoryRouter><SemanticAiPage /></MemoryRouter>);
    await screen.findByText('local-model-v1');
    fireEvent.click(screen.getByRole('button', { name: 'Actualizar' }));

    await waitFor(() => expect(screen.getByText('Búsqueda semántica no disponible')).toBeInTheDocument());
    expect(screen.getByText(/4 \/ 10/)).toBeInTheDocument();
    expect(semanticApi.fetchSemanticOverview).toHaveBeenCalledTimes(2);
  });

  it('expone un error de carga sin ocultar el título', async () => {
    vi.mocked(semanticApi.fetchSemanticOverview).mockRejectedValue(new Error('offline'));

    render(<MemoryRouter><SemanticAiPage /></MemoryRouter>);

    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudo consultar el estado semántico.');
    expect(screen.getByText('IA semántica')).toBeInTheDocument();
  });
});
