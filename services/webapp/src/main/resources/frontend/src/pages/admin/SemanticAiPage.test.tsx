import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as semanticApi from '../../api/semanticAdmin';
import type {
  SemanticBenchmarkMetric,
  SemanticBenchmarkRun,
  SemanticModel,
  SemanticOverview,
} from '../../types/semanticAdmin';
import { SemanticAiPage } from './SemanticAiPage';

vi.mock('../../api/semanticAdmin', () => ({
  fetchSemanticOverview: vi.fn(),
  fetchSemanticModels: vi.fn(),
  fetchSemanticBenchmarks: vi.fn(),
  fetchSemanticOperations: vi.fn(),
  startSemanticBenchmark: vi.fn(),
  prepareSemanticModel: vi.fn(),
  activateSemanticModel: vi.fn(),
  deleteSemanticModel: vi.fn(),
  cancelSemanticOperation: vi.fn(),
  retrySemanticOperation: vi.fn(),
}));

const activeModel: SemanticModel = {
  id: '00000000-0000-0000-0000-000000000001',
  displayName: 'multilingual-e5-base',
  repository: 'intfloat/multilingual-e5-base',
  revision: 'd128750597153bb5987e10b1c3493a34e5a4502a',
  artifactState: 'ready',
  deploymentState: 'active',
  artifactBytes: 1_100_000_000,
  dimensions: 768,
  queryPrefix: 'query: ',
  passagePrefix: 'passage: ',
  minimumSimilarity: 0.82,
  metadata: {},
  modelVersion: 'multilingual-e5-base@sha:zero-shot',
  active: true,
  createdAt: '2026-07-23T10:00:00Z',
  activatedAt: '2026-07-23T11:00:00Z',
  index: {
    indexVersion: 'index-1',
    snapshotHash: 'a'.repeat(64),
    expected: 13_667,
    indexed: 13_667,
    complete: true,
    builtAt: '2026-07-23T11:00:00Z',
  },
  lastBenchmark: {
    id: '10000000-0000-0000-0000-000000000001',
    datasetHash: 'b'.repeat(64),
    scope: 'full',
    current: true,
    metric: null,
    createdAt: '2026-07-23T10:30:00Z',
  },
};

const overview: SemanticOverview = {
  service: 'semantic-service',
  searchReady: true,
  activeModel,
  disk: {
    modelBytes: 1_100_000_000,
    freeBytes: 40_000_000_000,
    totalBytes: 100_000_000_000,
    reservedBytes: 10_000_000_000,
    maximumModelBytes: 16_000_000_000,
  },
  activeOperations: 0,
};

function benchmarkMetric(
  model: SemanticModel,
  totalScore: number,
): SemanticBenchmarkMetric {
  return {
    modelId: model.id,
    modelKey: model.displayName,
    modelVersion: model.modelVersion ?? '',
    repository: model.repository,
    variant: `${model.displayName}:semantic`,
    stage: 'base',
    scope: 'full',
    eligible: true,
    recommended: totalScore >= 0.8,
    ndcgAt10: totalScore,
    mrrAt10: totalScore,
    mapAt10: totalScore,
    recallAt10: totalScore,
    recallAt20: totalScore,
    exactMrrAt1: totalScore,
    p50Ms: 10,
    p95Ms: 20,
    p99Ms: 30,
    throughputQps: 100,
    embeddingBuildMs: 1000,
    hnswBuildMs: 100,
    indexBuildMs: 1100,
    hnswRecallAt20: 1,
    vectorBytes: 100,
    hnswIndexBytes: 100,
    indexBytes: 200,
    rssBytes: 1000,
    vramBytes: 0,
    totalScore,
    minimumSimilarity: model.minimumSimilarity,
  };
}

describe('semantic administration page', () => {
  beforeEach(() => {
    vi.mocked(semanticApi.fetchSemanticOverview).mockResolvedValue(overview);
    vi.mocked(semanticApi.fetchSemanticModels).mockResolvedValue([activeModel]);
    vi.mocked(semanticApi.fetchSemanticBenchmarks).mockResolvedValue([]);
    vi.mocked(semanticApi.fetchSemanticOperations).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the linked sections, service status and reconciled active model', async () => {
    render(
      <MemoryRouter initialEntries={['/admin/semantic/models']}>
        <Routes>
          <Route path="/admin/semantic/:semanticSection" element={<SemanticAiPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'IA semántica' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Modelos/ })).toHaveAttribute(
      'href',
      '/admin/semantic/models',
    );
    expect(screen.getByRole('link', { name: /Comparativa/ })).toHaveAttribute(
      'href',
      '/admin/semantic/benchmarks',
    );
    expect(screen.getByRole('navigation', { name: 'Secciones de IA semántica' })
      .querySelectorAll('a')).toHaveLength(2);
    expect(screen.getAllByText('multilingual-e5-base').length).toBeGreaterThan(0);
    await waitFor(() => {
      expect(semanticApi.fetchSemanticOperations).toHaveBeenCalledTimes(1);
    });
  });

  it('carries an evaluated candidate into a valid two-model comparison', async () => {
    const candidate: SemanticModel = {
      ...activeModel,
      id: '00000000-0000-0000-0000-000000000002',
      displayName: 'candidate-model',
      repository: 'owner/candidate-model',
      active: false,
      deploymentState: 'not_prepared',
      activatedAt: null,
      lastBenchmark: null,
      index: {
        expected: 0,
        indexed: 0,
        complete: false,
      },
    };
    vi.mocked(semanticApi.fetchSemanticModels).mockResolvedValue([
      activeModel,
      candidate,
    ]);

    render(
      <MemoryRouter initialEntries={[{
        pathname: '/admin/semantic/benchmarks',
        state: { candidateModelId: candidate.id },
      }]}>
        <Routes>
          <Route path="/admin/semantic/:semanticSection" element={<SemanticAiPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const run = await screen.findByRole('button', { name: 'Ejecutar benchmark' });
    expect(run).toBeEnabled();
    expect(screen.getByRole('checkbox', { name: /multilingual-e5-base/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /candidate-model/ })).toBeChecked();
  });

  it('keeps activation actionable and routes candidates without current evidence to comparison', async () => {
    const candidate: SemanticModel = {
      ...activeModel,
      id: '00000000-0000-0000-0000-000000000002',
      displayName: 'candidate-model',
      repository: 'owner/candidate-model',
      active: false,
      deploymentState: 'not_prepared',
      activatedAt: null,
      lastBenchmark: null,
    };
    vi.mocked(semanticApi.fetchSemanticModels).mockResolvedValue([activeModel, candidate]);

    render(
      <MemoryRouter initialEntries={['/admin/semantic/models']}>
        <Routes>
          <Route path="/admin/semantic/:semanticSection" element={<SemanticAiPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const activate = await screen.findByRole('button', { name: 'Activar' });
    expect(activate).toBeEnabled();
    expect(activate).toHaveAttribute(
      'title',
      'Abrir la comparativa para generar evidencia antes de activar',
    );
    fireEvent.click(activate);

    expect(await screen.findByRole('button', { name: 'Ejecutar benchmark' })).toBeEnabled();
    expect(screen.getByRole('checkbox', { name: /candidate-model/ })).toBeChecked();
  });

  it('prepares a current evaluated candidate from the activation action', async () => {
    const runId = '10000000-0000-0000-0000-000000000098';
    const candidateBase: SemanticModel = {
      ...activeModel,
      id: '00000000-0000-0000-0000-000000000098',
      displayName: 'candidate-model',
      repository: 'owner/candidate-model',
      active: false,
      deploymentState: 'not_prepared',
      activatedAt: null,
    };
    const activeMetric = benchmarkMetric(activeModel, 0.8);
    const candidateMetric = benchmarkMetric(candidateBase, 0.9);
    const candidate: SemanticModel = {
      ...candidateBase,
      lastBenchmark: {
        id: runId,
        datasetHash: 'b'.repeat(64),
        scope: 'full',
        current: true,
        metric: candidateMetric,
        createdAt: '2026-07-26T10:00:00Z',
      },
    };
    vi.mocked(semanticApi.fetchSemanticModels).mockResolvedValue([activeModel, candidate]);
    vi.mocked(semanticApi.fetchSemanticBenchmarks).mockResolvedValue([{
      id: runId,
      datasetHash: 'b'.repeat(64),
      seed: 20260723,
      configuration: {},
      metrics: [activeMetric, candidateMetric],
      modelIds: [activeModel.id, candidate.id],
      scope: 'full',
      hardwareFingerprint: 'fixture',
      documentCount: 100,
      queryCount: 20,
      metricsSchemaVersion: 2,
      createdAt: '2026-07-26T10:00:00Z',
    }]);
    vi.mocked(semanticApi.prepareSemanticModel).mockResolvedValue({
      operationId: 'prepare-operation',
      status: 'queued',
    });

    render(
      <MemoryRouter initialEntries={['/admin/semantic/models']}>
        <Routes>
          <Route path="/admin/semantic/:semanticSection" element={<SemanticAiPage />} />
        </Routes>
      </MemoryRouter>,
    );

    const activate = await screen.findByRole('button', { name: 'Activar' });
    expect(activate).toBeEnabled();
    expect(activate).toHaveAttribute('title', 'Preparar el índice antes de activar');
    fireEvent.click(activate);

    await waitFor(() => {
      expect(semanticApi.prepareSemanticModel).toHaveBeenCalledWith(candidate.id);
    });
  });

  it('requires the explicit second confirmation for a measured regression', async () => {
    const runId = '10000000-0000-0000-0000-000000000099';
    const activeMetric = benchmarkMetric(activeModel, 0.8);
    const candidateBase: SemanticModel = {
      ...activeModel,
      id: '00000000-0000-0000-0000-000000000099',
      displayName: 'regression-candidate',
      repository: 'owner/regression-candidate',
      active: false,
      deploymentState: 'ready',
      activatedAt: null,
    };
    const candidateMetric = benchmarkMetric(candidateBase, 0.5);
    const candidate: SemanticModel = {
      ...candidateBase,
      lastBenchmark: {
        id: runId,
        datasetHash: 'c'.repeat(64),
        scope: 'full',
        current: true,
        metric: candidateMetric,
        createdAt: '2026-07-26T10:00:00Z',
      },
    };
    const run: SemanticBenchmarkRun = {
      id: runId,
      datasetHash: 'c'.repeat(64),
      seed: 20260723,
      configuration: {},
      metrics: [activeMetric, candidateMetric],
      modelIds: [activeModel.id, candidate.id],
      scope: 'full',
      hardwareFingerprint: 'fixture',
      documentCount: 100,
      queryCount: 20,
      metricsSchemaVersion: 2,
      createdAt: '2026-07-26T10:00:00Z',
    };
    vi.mocked(semanticApi.fetchSemanticModels).mockResolvedValue([
      activeModel,
      candidate,
    ]);
    vi.mocked(semanticApi.fetchSemanticBenchmarks).mockResolvedValue([run]);
    vi.mocked(semanticApi.activateSemanticModel).mockResolvedValue({
      operationId: 'activation-operation',
      status: 'queued',
    });

    render(
      <MemoryRouter initialEntries={['/admin/semantic/models']}>
        <Routes>
          <Route path="/admin/semantic/:semanticSection" element={<SemanticAiPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Activar' }));
    expect(screen.getByText(/score inferior al modelo activo/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Continuar' }));

    await waitFor(() => {
      expect(semanticApi.activateSemanticModel).toHaveBeenCalledWith(
        candidate.id,
        expect.objectContaining({
          benchmarkRunId: runId,
          expectedCurrentModelId: activeModel.id,
          confirmRegression: true,
        }),
      );
    });
  });
});
