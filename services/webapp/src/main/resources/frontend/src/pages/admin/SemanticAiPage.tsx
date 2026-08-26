/*
 * THESIS: Un modelo no es una opción de menú, sino una entrega verificable; la vista
 * separa el inventario local, la evidencia y la activación en producción.
 * OWN-WORLD: Hereda el administrador claro y teal, con una banda de estado, tablas
 * densas, etiquetas semánticas y barras comparativas contenidas.
 * STORY: El operador identifica el modelo activo, reúne evidencia, prepara el
 * candidato y confirma el cambio sin perder de vista producción.
 * FIRST VIEWPORT: Estado operativo arriba, navegación de dos tareas debajo y el
 * contenido principal acompañado por una cola de trabajos recuperable.
 * FORM: Superficie Operate de control progresivo; biblioteca y comparativa
 * permanecen rutas familiares dentro del mundo visual existente.
 */
import {
  Activity,
  AlertTriangle,
  BrainCircuit,
  CheckCircle2,
  Cpu,
  Database,
  Gauge,
  HardDrive,
  Library,
  LoaderCircle,
  Play,
  RotateCcw,
  ShieldCheck,
  Trash2,
  X,
  XCircle,
} from 'lucide-react';
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  NavLink,
  Navigate,
  useLocation,
  useNavigate,
  useParams,
} from 'react-router-dom';
import { ApiRequestError } from '../../api/http';
import {
  activateSemanticModel,
  cancelSemanticOperation,
  deleteSemanticModel,
  fetchSemanticBenchmarks,
  fetchSemanticModels,
  fetchSemanticOperations,
  fetchSemanticOverview,
  prepareSemanticModel,
  retrySemanticOperation,
  startSemanticBenchmark,
} from '../../api/semanticAdmin';
import { usePollingTask } from '../../hooks/usePollingTask';
import { useTranslation, type Translator } from '../../services/i18n';
import type {
  SemanticBenchmarkMetric,
  SemanticBenchmarkRun,
  SemanticModel,
  SemanticOperation,
  SemanticOverview,
} from '../../types/semanticAdmin';

const TERMINAL_OPERATIONS = new Set(['cancelled', 'succeeded', 'failed']);
const NON_CANCELLABLE_PHASES = new Set([
  'activating',
  'deleting',
  'finalizing',
  'publishing',
]);
const SECTIONS = new Set(['models', 'benchmarks']);

export function SemanticAiPage() {
  const t = useTranslation();
  const { semanticSection = 'models' } = useParams<{ semanticSection: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [overview, setOverview] = useState<SemanticOverview | null>(null);
  const [models, setModels] = useState<SemanticModel[]>([]);
  const [benchmarks, setBenchmarks] = useState<SemanticBenchmarkRun[]>([]);
  const [operations, setOperations] = useState<SemanticOperation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const [nextOverview, nextModels, nextBenchmarks, nextOperations] = await Promise.all([
        fetchSemanticOverview(),
        fetchSemanticModels(),
        fetchSemanticBenchmarks(),
        fetchSemanticOperations(),
      ]);
      setOverview(nextOverview);
      setModels(nextModels);
      setBenchmarks(nextBenchmarks);
      setOperations(nextOperations);
      setError(null);
    } catch (cause) {
      setError(errorMessage(t, cause));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const hasActiveOperations = operations.some(
    (operation) => !TERMINAL_OPERATIONS.has(operation.status),
  );

  usePollingTask({
    enabled: hasActiveOperations,
    intervalMs: 2_000,
    pollKey: 'semantic-operations',
    task: () => refresh(true),
  });

  const runAction = useCallback(async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      await refresh(true);
      setError(null);
    } catch (cause) {
      setError(errorMessage(t, cause));
    } finally {
      setBusy(false);
    }
  }, [refresh, t]);

  if (!SECTIONS.has(semanticSection)) {
    return <Navigate to="/admin/semantic/models" replace />;
  }

  return (
    <section className="admin-panel semantic-admin">
      <SemanticHeader overview={overview} loading={loading} />
      <nav className="semantic-tabs" aria-label={t('semantic.tabs.label')}>
        <NavLink to="/admin/semantic/models">
          <Library size={17} />
          {t('semantic.tabs.models')}
        </NavLink>
        <NavLink to="/admin/semantic/benchmarks">
          <Gauge size={17} />
          {t('semantic.tabs.benchmarks')}
        </NavLink>
      </nav>

      {error ? (
        <div className="error-banner semantic-error" role="alert">
          <AlertTriangle size={18} />
          <span>{error}</span>
          <button type="button" aria-label={t('common.close')} onClick={() => setError(null)}>
            <X size={17} />
          </button>
        </div>
      ) : null}

      <div className="semantic-workspace">
        <div className="semantic-main">
          {loading && !overview ? <SemanticSkeleton /> : null}
          {!loading || overview ? (
            <>
              {semanticSection === 'models' ? (
                <ModelLibrary
                  models={models}
                  benchmarks={benchmarks}
                  activeModelId={overview?.activeModel?.id ?? null}
                  busy={busy}
                  operations={operations}
                  onEvaluate={(modelId) => {
                    navigate('/admin/semantic/benchmarks', {
                      state: { candidateModelId: modelId },
                    });
                  }}
                  onPrepare={(modelId) => runAction(() => prepareSemanticModel(modelId))}
                  onActivate={(model, confirmRegression) => runAction(() => activateSemanticModel(
                    model.id,
                    {
                      benchmarkRunId: model.lastBenchmark?.id ?? '',
                      expectedCurrentModelId: overview?.activeModel?.id ?? null,
                      confirmRegression,
                      activationKind: model.activatedAt ? 'rollback' : 'activate',
                    },
                  ))}
                  onDelete={(modelId) => runAction(() => deleteSemanticModel(modelId))}
                />
              ) : null}
              {semanticSection === 'benchmarks' ? (
                <BenchmarkComparison
                  models={models}
                  runs={benchmarks}
                  activeModelId={overview?.activeModel?.id ?? null}
                  busy={busy}
                  initialCandidateId={(
                    location.state as { candidateModelId?: string } | null
                  )?.candidateModelId}
                  onStart={(modelIds) => runAction(() => startSemanticBenchmark(modelIds))}
                />
              ) : null}
            </>
          ) : null}
        </div>
        <OperationTray
          operations={operations}
          onCancel={(operationId) => runAction(() => cancelSemanticOperation(operationId))}
          onRetry={(operationId) => runAction(() => retrySemanticOperation(operationId))}
        />
      </div>
    </section>
  );
}

function SemanticHeader({
  overview,
  loading,
}: {
  overview: SemanticOverview | null;
  loading: boolean;
}) {
  const t = useTranslation();
  const active = overview?.activeModel;
  const coverage = active?.index.expected
    ? active.index.indexed / active.index.expected
    : 0;
  return (
    <header className="semantic-control-header">
      <div className="semantic-title">
        <span className="semantic-title-mark" aria-hidden="true">
          <BrainCircuit size={24} />
        </span>
        <div>
          <h2>{t('semantic.title')}</h2>
          <p>{t('semantic.subtitle')}</p>
        </div>
      </div>
      <dl className="semantic-status-strip" aria-busy={loading}>
        <div className="semantic-status-primary">
          <dt><Activity size={16} />{t('semantic.status.activeModel')}</dt>
          <dd>{active?.displayName ?? t('semantic.status.noActiveModel')}</dd>
          <small className={overview?.searchReady ? 'status-ok' : 'status-warning'}>
            {overview?.searchReady
              ? t('semantic.status.searchReady')
              : t('semantic.status.searchDegraded')}
          </small>
        </div>
        <div>
          <dt><Database size={16} />{t('semantic.status.coverage')}</dt>
          <dd>{formatPercent(coverage)}</dd>
          <small>{active ? `${active.index.indexed.toLocaleString('es-ES')} / ${active.index.expected.toLocaleString('es-ES')}` : '—'}</small>
        </div>
        <div>
          <dt><HardDrive size={16} />{t('semantic.status.storage')}</dt>
          <dd>{formatBytes(overview?.disk.modelBytes ?? 0)}</dd>
          <small>{t('semantic.status.free', { value: formatBytes(overview?.disk.freeBytes ?? 0) })}</small>
        </div>
        <div>
          <dt><LoaderCircle size={16} />{t('semantic.status.operations')}</dt>
          <dd>{overview?.activeOperations ?? 0}</dd>
          <small>{t('semantic.status.persisted')}</small>
        </div>
      </dl>
    </header>
  );
}

function ModelLibrary({
  models,
  benchmarks,
  activeModelId,
  operations,
  busy,
  onEvaluate,
  onPrepare,
  onActivate,
  onDelete,
}: {
  models: SemanticModel[];
  benchmarks: SemanticBenchmarkRun[];
  activeModelId: string | null;
  operations: SemanticOperation[];
  busy: boolean;
  onEvaluate: (modelId: string) => void;
  onPrepare: (modelId: string) => void;
  onActivate: (model: SemanticModel, confirmRegression: boolean) => void;
  onDelete: (modelId: string) => void;
}) {
  const t = useTranslation();
  const [confirmation, setConfirmation] = useState<{
    modelId: string;
    kind: 'activate' | 'delete';
  } | null>(null);
  const operationModels = new Set(
    operations
      .filter((operation) => !TERMINAL_OPERATIONS.has(operation.status))
      .flatMap((operation) => operation.modelIds ?? [operation.modelId])
      .filter(Boolean),
  );
  if (!models.length) {
    return (
      <div className="semantic-empty">
        <Library size={28} />
        <h3>{t('semantic.models.emptyTitle')}</h3>
        <p>{t('semantic.models.emptyBody')}</p>
      </div>
    );
  }

  return (
    <section className="semantic-section" aria-labelledby="semantic-models-title">
      <div className="semantic-section-heading">
        <div>
          <h3 id="semantic-models-title">{t('semantic.models.title')}</h3>
          <p>{t('semantic.models.description')}</p>
        </div>
        <span>{t('semantic.models.count', { count: models.length })}</span>
      </div>
      <div className="semantic-model-list">
        {models.map((model) => {
          const hasFullBenchmark = model.lastBenchmark?.scope === 'full'
            && model.lastBenchmark.current;
          const prepared = model.deploymentState === 'ready';
          const running = operationModels.has(model.id);
          const metric = model.lastBenchmark?.metric;
          const evidenceRun = model.lastBenchmark
            ? benchmarks.find((run) => run.id === model.lastBenchmark?.id)
            : undefined;
          const activeMetric = evidenceRun?.metrics.find(
            (candidate) => candidate.modelId === activeModelId,
          );
          const regression = Boolean(
            metric
            && activeMetric
            && metric.totalScore < activeMetric.totalScore,
          );
          const confirming = confirmation?.modelId === model.id;
          return (
            <article
              className={`semantic-model-row ${model.active ? 'semantic-model-active' : ''}`}
              key={model.id}
            >
              <div className="semantic-model-identity">
                <span className="semantic-model-icon" aria-hidden="true">
                  <Cpu size={20} />
                </span>
                <div>
                  <div className="semantic-model-name">
                    <h4>{model.displayName}</h4>
                    <StatusBadge
                      tone={artifactTone(model.artifactState)}
                      label={t(`semantic.artifact.${model.artifactState}`)}
                    />
                    <StatusBadge
                      tone={deploymentTone(model.deploymentState)}
                      label={t(`semantic.deployment.${model.deploymentState}`)}
                    />
                  </div>
                  <p>{model.repository}</p>
                  <code title={model.revision}>{model.revision.slice(0, 12)}</code>
                </div>
              </div>
              <dl className="semantic-model-facts">
                <div>
                  <dt>{t('semantic.models.dimensions')}</dt>
                  <dd>{model.dimensions?.toLocaleString('es-ES') ?? '—'}</dd>
                </div>
                <div>
                  <dt>{t('semantic.models.size')}</dt>
                  <dd>{formatBytes(model.artifactBytes)}</dd>
                </div>
                <div>
                  <dt>{t('semantic.models.similarity')}</dt>
                  <dd>{model.minimumSimilarity.toFixed(2)}</dd>
                </div>
                <div>
                  <dt>{t('semantic.models.coverage')}</dt>
                  <dd>{model.index.expected ? formatPercent(model.index.indexed / model.index.expected) : '—'}</dd>
                </div>
              </dl>
              <div className="semantic-model-evidence">
                <span>{t('semantic.models.evidence')}</span>
                {metric ? (
                  <>
                    <strong>{metric.totalScore.toFixed(3)}</strong>
                    <small>
                      {model.lastBenchmark?.scope !== 'full'
                        ? t('semantic.benchmark.diagnostic')
                        : model.lastBenchmark.current
                          ? t('semantic.benchmark.full')
                          : t('semantic.benchmark.stale')}
                    </small>
                  </>
                ) : (
                  <small>{t('semantic.models.noBenchmark')}</small>
                )}
              </div>
              <div className="semantic-model-actions">
                <button
                  className="secondary-button compact-button"
                  type="button"
                  disabled={busy || running || model.artifactState !== 'ready'}
                  onClick={() => onEvaluate(model.id)}
                >
                  <Gauge size={16} />
                  {t('semantic.actions.evaluate')}
                </button>
                <button
                  className="secondary-button compact-button"
                  type="button"
                  disabled={busy || running || !hasFullBenchmark || model.active}
                  onClick={() => onPrepare(model.id)}
                >
                  <Database size={16} />
                  {t('semantic.actions.prepare')}
                </button>
                <button
                  className="primary-button compact-button"
                  type="button"
                  disabled={busy || running || model.artifactState !== 'ready' || model.active}
                  title={
                    !hasFullBenchmark
                      ? t('semantic.actions.activationNeedsBenchmark')
                      : !prepared
                        ? t('semantic.actions.activationNeedsPreparation')
                        : t('semantic.actions.activationReady')
                  }
                  onClick={() => {
                    if (!hasFullBenchmark) {
                      onEvaluate(model.id);
                      return;
                    }
                    if (!prepared) {
                      onPrepare(model.id);
                      return;
                    }
                    setConfirmation({ modelId: model.id, kind: 'activate' });
                  }}
                >
                  {model.activatedAt ? <RotateCcw size={16} /> : <Play size={16} />}
                  {model.activatedAt ? t('semantic.actions.rollback') : t('semantic.actions.activate')}
                </button>
                <button
                  className="icon-action semantic-delete-action"
                  type="button"
                  aria-label={t('semantic.actions.deleteModel', { name: model.displayName })}
                  disabled={busy || running || model.active}
                  onClick={() => setConfirmation({ modelId: model.id, kind: 'delete' })}
                >
                  <Trash2 size={16} />
                </button>
              </div>
              {confirming ? (
                <div className={`semantic-inline-confirm ${confirmation.kind === 'delete' ? 'semantic-confirm-danger' : ''}`}>
                  <AlertTriangle size={18} />
                  <div>
                    <strong>
                      {confirmation.kind === 'delete'
                        ? t('semantic.confirm.deleteTitle')
                        : t('semantic.confirm.activateTitle')}
                    </strong>
                    <p>
                      {confirmation.kind === 'delete'
                        ? t('semantic.confirm.deleteBody')
                        : regression
                          ? t('semantic.confirm.regressionBody')
                          : t('semantic.confirm.activateBody')}
                    </p>
                  </div>
                  <button
                    className={confirmation.kind === 'delete' ? 'danger-button compact-button' : 'primary-button compact-button'}
                    type="button"
                    onClick={() => {
                      if (confirmation.kind === 'delete') onDelete(model.id);
                      else onActivate(model, regression);
                      setConfirmation(null);
                    }}
                  >
                    {t('semantic.confirm.continue')}
                  </button>
                  <button
                    className="secondary-button compact-button"
                    type="button"
                    onClick={() => setConfirmation(null)}
                  >
                    {t('semantic.confirm.cancel')}
                  </button>
                </div>
              ) : null}
            </article>
          );
        })}
      </div>
    </section>
  );
}

const METRICS: Array<{
  key: keyof SemanticBenchmarkMetric;
  label: string;
  format: (value: number) => string;
  higher: boolean;
}> = [
  { key: 'totalScore', label: 'semantic.metric.totalScore', format: decimal, higher: true },
  { key: 'ndcgAt10', label: 'semantic.metric.ndcgAt10', format: decimal, higher: true },
  { key: 'mrrAt10', label: 'semantic.metric.mrrAt10', format: decimal, higher: true },
  { key: 'mapAt10', label: 'semantic.metric.mapAt10', format: decimal, higher: true },
  { key: 'recallAt10', label: 'semantic.metric.recallAt10', format: decimal, higher: true },
  { key: 'recallAt20', label: 'semantic.metric.recallAt20', format: decimal, higher: true },
  { key: 'exactMrrAt1', label: 'semantic.metric.exactMrrAt1', format: decimal, higher: true },
  { key: 'loadMs', label: 'semantic.metric.load', format: milliseconds, higher: false },
  { key: 'warmupMs', label: 'semantic.metric.warmup', format: milliseconds, higher: false },
  { key: 'p50Ms', label: 'semantic.metric.p50', format: milliseconds, higher: false },
  { key: 'p95Ms', label: 'semantic.metric.p95', format: milliseconds, higher: false },
  { key: 'p99Ms', label: 'semantic.metric.p99', format: milliseconds, higher: false },
  { key: 'throughputQps', label: 'semantic.metric.qps', format: decimal, higher: true },
  { key: 'embeddingBuildMs', label: 'semantic.metric.embeddingBuild', format: milliseconds, higher: false },
  { key: 'hnswBuildMs', label: 'semantic.metric.hnswBuild', format: milliseconds, higher: false },
  { key: 'artifactBytes', label: 'semantic.metric.artifactBytes', format: formatBytes, higher: false },
  { key: 'dimensions', label: 'semantic.metric.dimensions', format: integer, higher: false },
  { key: 'vectorBytes', label: 'semantic.metric.vectorBytes', format: formatBytes, higher: false },
  { key: 'hnswIndexBytes', label: 'semantic.metric.hnswBytes', format: formatBytes, higher: false },
  { key: 'rssBytes', label: 'semantic.metric.rss', format: formatBytes, higher: false },
  { key: 'vramBytes', label: 'semantic.metric.vram', format: formatBytes, higher: false },
  { key: 'indexBytes', label: 'semantic.metric.indexBytes', format: formatBytes, higher: false },
];

function BenchmarkComparison({
  models,
  runs,
  activeModelId,
  busy,
  initialCandidateId,
  onStart,
}: {
  models: SemanticModel[];
  runs: SemanticBenchmarkRun[];
  activeModelId: string | null;
  busy: boolean;
  initialCandidateId?: string;
  onStart: (modelIds: string[]) => void;
}) {
  const t = useTranslation();
  const eligibleModels = useMemo(
    () => models.filter((model) => model.artifactState === 'ready'),
    [models],
  );
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [selectedRunId, setSelectedRunId] = useState('');

  useEffect(() => {
    setSelectedModels((current) => {
      if (current.length) return current.filter((id) => eligibleModels.some((model) => model.id === id));
      const defaults = activeModelId ? [activeModelId] : [];
      if (
        initialCandidateId
        && initialCandidateId !== activeModelId
        && eligibleModels.some((model) => model.id === initialCandidateId)
      ) {
        defaults.push(initialCandidateId);
      }
      return defaults.length ? defaults : eligibleModels.slice(0, 1).map((model) => model.id);
    });
  }, [activeModelId, eligibleModels, initialCandidateId]);

  useEffect(() => {
    if (!selectedRunId && runs.length) setSelectedRunId(runs[0].id);
  }, [runs, selectedRunId]);

  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? runs[0];
  const comparable = selectedRun?.scope === 'full';

  return (
    <section className="semantic-section" aria-labelledby="semantic-benchmark-title">
      <div className="semantic-section-heading semantic-benchmark-heading">
        <div>
          <h3 id="semantic-benchmark-title">{t('semantic.comparison.title')}</h3>
          <p>{t('semantic.comparison.description')}</p>
        </div>
        <button
          className="primary-button"
          type="button"
          disabled={busy || selectedModels.length < 2}
          onClick={() => onStart(selectedModels)}
        >
          <Play size={17} />
          {t('semantic.comparison.run')}
        </button>
      </div>
      <div className="semantic-benchmark-controls">
        <fieldset>
          <legend>{t('semantic.comparison.models')}</legend>
          {eligibleModels.map((model) => {
            const selected = selectedModels.includes(model.id);
            return (
              <label key={model.id}>
                <input
                  type="checkbox"
                  checked={selected}
                  disabled={model.id === activeModelId || (!selected && selectedModels.length >= 4)}
                  onChange={() => setSelectedModels((current) => (
                    selected
                      ? current.filter((id) => id !== model.id)
                      : [...current, model.id]
                  ))}
                />
                <span>{model.displayName}</span>
                {model.active ? <small>{t('semantic.state.active')}</small> : null}
              </label>
            );
          })}
        </fieldset>
        <label className="semantic-run-selector">
          <span>{t('semantic.comparison.savedRun')}</span>
          <select
            value={selectedRun?.id ?? ''}
            onChange={(event) => setSelectedRunId(event.target.value)}
          >
            {runs.map((run) => (
              <option value={run.id} key={run.id}>
                {new Date(run.createdAt).toLocaleString('es-ES')} · {run.scope}
              </option>
            ))}
          </select>
        </label>
      </div>
      {!runs.length ? (
        <div className="semantic-empty">
          <Gauge size={28} />
          <h3>{t('semantic.comparison.emptyTitle')}</h3>
          <p>{t('semantic.comparison.emptyBody')}</p>
        </div>
      ) : null}
      {selectedRun ? (
        <>
          <div className={`semantic-evidence-banner ${comparable ? 'semantic-evidence-valid' : 'semantic-evidence-warning'}`}>
            {comparable ? <ShieldCheck size={18} /> : <AlertTriangle size={18} />}
            <div>
              <strong>
                {comparable
                  ? t('semantic.comparison.comparable')
                  : t('semantic.comparison.diagnostic')}
              </strong>
              <span>
                {t('semantic.comparison.scope', {
                  documents: selectedRun.documentCount.toLocaleString('es-ES'),
                  queries: selectedRun.queryCount.toLocaleString('es-ES'),
                  seed: selectedRun.seed,
                })}
              </span>
            </div>
            <code>{selectedRun.datasetHash.slice(0, 12)}</code>
          </div>
          <BenchmarkTable run={selectedRun} />
        </>
      ) : null}
    </section>
  );
}

function BenchmarkTable({ run }: { run: SemanticBenchmarkRun }) {
  const t = useTranslation();
  return (
    <div className="semantic-comparison-table-shell">
      <table className="semantic-comparison-table">
        <caption className="sr-only">{t('semantic.comparison.tableCaption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('semantic.comparison.metric')}</th>
            {run.metrics.map((metric) => (
              <th scope="col" key={metric.modelId}>
                <span>{metric.repository}</span>
                <small>
                  {run.scope === 'full' && metric.recommended && metric.eligible
                    ? t('semantic.comparison.recommended')
                    : metric.scope}
                </small>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {METRICS.map((definition) => {
            const values = run.metrics.map((metric) => Number(metric[definition.key]) || 0);
            const max = Math.max(...values, 0.000001);
            const positives = values.filter((value) => value > 0);
            const min = positives.length ? Math.min(...positives) : 0;
            return (
              <tr key={definition.key}>
                <th scope="row">
                  {t(definition.label)}
                  <small>
                    {definition.higher
                      ? t('semantic.comparison.higherBetter')
                      : t('semantic.comparison.lowerBetter')}
                  </small>
                </th>
                {run.metrics.map((metric, index) => {
                  const value = values[index];
                  const relative = definition.higher
                    ? value / max
                    : value > 0 && min > 0
                      ? min / value
                      : 0;
                  return (
                    <td key={metric.modelId}>
                      <strong>{definition.format(value)}</strong>
                      <span className="semantic-metric-track" aria-hidden="true">
                        <span style={{ width: `${Math.max(4, relative * 100)}%` }} />
                      </span>
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function OperationTray({
  operations,
  onCancel,
  onRetry,
}: {
  operations: SemanticOperation[];
  onCancel: (operationId: string) => void;
  onRetry: (operationId: string) => void;
}) {
  const t = useTranslation();
  const visible = [
    ...operations.filter((operation) => !TERMINAL_OPERATIONS.has(operation.status)),
    ...operations.filter(
      (operation) => operation.status === 'failed' || operation.status === 'cancelled',
    ).slice(0, 3),
  ].slice(0, 8);
  return (
    <aside className="semantic-operation-tray" aria-labelledby="semantic-operations-title">
      <div className="semantic-operation-heading">
        <div>
          <h3 id="semantic-operations-title">{t('semantic.operations.title')}</h3>
          <p>{t('semantic.operations.description')}</p>
        </div>
        <span>{visible.filter((operation) => !TERMINAL_OPERATIONS.has(operation.status)).length}</span>
      </div>
      {!visible.length ? (
        <div className="semantic-operation-empty">
          <CheckCircle2 size={22} />
          <strong>{t('semantic.operations.emptyTitle')}</strong>
          <p>{t('semantic.operations.emptyBody')}</p>
        </div>
      ) : null}
      <div className="semantic-operation-list">
        {visible.map((operation) => {
          const percent = operation.progress.total
            ? Math.min(1, operation.progress.current / operation.progress.total)
            : 0;
          const active = !TERMINAL_OPERATIONS.has(operation.status);
          return (
            <article className={`semantic-operation semantic-operation-${operation.status}`} key={operation.id}>
              <div className="semantic-operation-title">
                <span>{operationIcon(operation)}</span>
                <div>
                  <strong>{t(`semantic.operation.${operation.kind}`)}</strong>
                  <small>{operation.repository ?? shortId(operation.modelId)}</small>
                </div>
                <StatusBadge
                  tone={
                    operation.status === 'failed'
                      ? 'danger'
                      : operation.status === 'cancelled'
                        ? 'neutral'
                        : 'info'
                  }
                  label={t(`semantic.operation.status.${operation.status}`)}
                />
              </div>
              <p>{operation.message ?? t(`semantic.operation.phase.${operation.phase}`)}</p>
              {active ? (
                <>
                  <span className="semantic-operation-progress" aria-hidden="true">
                    <span style={{ transform: `scaleX(${percent})` }} />
                  </span>
                  <div className="semantic-operation-meta">
                    <span>
                      {operation.progress.total
                        ? `${formatProgress(operation.progress.current, operation.progress.unit)} / ${formatProgress(operation.progress.total, operation.progress.unit)}`
                        : t(`semantic.operation.phase.${operation.phase}`)}
                    </span>
                    <button
                      type="button"
                      disabled={
                        operation.status === 'cancel_requested'
                        || NON_CANCELLABLE_PHASES.has(operation.phase)
                      }
                      onClick={() => onCancel(operation.id)}
                    >
                      <X size={14} />{t('semantic.operations.cancel')}
                    </button>
                  </div>
                </>
              ) : null}
              {operation.errorCode ? <code>{operation.errorCode}</code> : null}
              {operation.status === 'failed' || operation.status === 'cancelled' ? (
                <button
                  className="secondary-button compact-button semantic-retry-operation"
                  type="button"
                  onClick={() => onRetry(operation.id)}
                >
                  <RotateCcw size={14} />{t('semantic.operations.retry')}
                </button>
              ) : null}
            </article>
          );
        })}
      </div>
    </aside>
  );
}

function StatusBadge({
  tone,
  label,
}: {
  tone: 'neutral' | 'success' | 'warning' | 'danger' | 'info';
  label: string;
}) {
  return <span className={`semantic-badge semantic-badge-${tone}`}>{label}</span>;
}

function SemanticSkeleton() {
  const t = useTranslation();
  return (
    <div className="semantic-skeleton" aria-label={t('common.loading')}>
      <span />
      <span />
      <span />
    </div>
  );
}

function operationIcon(operation: SemanticOperation) {
  if (operation.status === 'failed') return <XCircle size={16} />;
  if (operation.kind === 'benchmark') return <Gauge size={16} />;
  if (operation.kind === 'prepare') return <Database size={16} />;
  if (operation.kind === 'activate') return <Play size={16} />;
  return <Trash2 size={16} />;
}

function artifactTone(state: SemanticModel['artifactState']) {
  if (state === 'ready') return 'success' as const;
  if (state === 'failed' || state === 'incompatible') return 'danger' as const;
  return 'info' as const;
}

function deploymentTone(state: SemanticModel['deploymentState']) {
  if (state === 'active' || state === 'ready') return 'success' as const;
  if (state === 'failed') return 'danger' as const;
  if (state === 'stale') return 'warning' as const;
  if (state === 'preparing') return 'info' as const;
  return 'neutral' as const;
}

function errorMessage(t: Translator, cause: unknown): string {
  if (cause instanceof ApiRequestError) {
    const key = `semantic.error.${cause.code}`;
    const translated = t(key);
    return translated === key
      ? t('semantic.error.generic', { code: cause.code })
      : translated;
  }
  return t('semantic.error.unavailable');
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / (1024 ** index)).toLocaleString('es-ES', { maximumFractionDigits: index ? 1 : 0 })} ${units[index]}`;
}

function formatPercent(value: number): string {
  return value.toLocaleString('es-ES', { style: 'percent', maximumFractionDigits: 1 });
}

function integer(value: number): string {
  return Math.round(value).toLocaleString('es-ES');
}

function decimal(value: number): string {
  return value.toLocaleString('es-ES', { minimumFractionDigits: 3, maximumFractionDigits: 3 });
}

function milliseconds(value: number): string {
  return `${value.toLocaleString('es-ES', { maximumFractionDigits: 1 })} ms`;
}

function formatProgress(value: number, unit: string): string {
  return unit === 'bytes' ? formatBytes(value) : value.toLocaleString('es-ES');
}

function shortId(value?: string | null): string {
  return value ? value.slice(0, 8) : '—';
}
