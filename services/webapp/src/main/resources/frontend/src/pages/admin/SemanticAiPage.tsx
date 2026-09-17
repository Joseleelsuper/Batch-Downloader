import { BrainCircuit, CheckCircle2, RefreshCw, XCircle } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { ApiRequestError } from '../../api/http';
import { fetchSemanticOverview } from '../../api/semanticAdmin';
import { useTranslation, type Translator } from '../../services/i18n';
import type { SemanticOverview } from '../../types/semanticAdmin';

export function SemanticAiPage() {
  const t = useTranslation();
  const [overview, setOverview] = useState<SemanticOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setOverview(await fetchSemanticOverview());
    } catch (cause) {
      setError(errorMessage(t, cause));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="admin-panel semantic-admin">
      <SemanticHeader loading={loading} onRefresh={load} t={t} />
      {error ? <SemanticError message={error} /> : null}
      <SemanticContent loading={loading} overview={overview} t={t} />
    </section>
  );
}

function SemanticHeader({
  loading,
  onRefresh,
  t,
}: Readonly<{ loading: boolean; onRefresh: () => Promise<void>; t: Translator }>) {
  return (
    <header className="semantic-admin-header">
      <div className="semantic-admin-title">
        <BrainMark />
        <div>
          <h2>{t('semantic.title')}</h2>
          <p>{t('semantic.subtitle')}</p>
        </div>
      </div>
      <button className="secondary-button" type="button" onClick={() => void onRefresh()} disabled={loading}>
        <RefreshCw size={16} aria-hidden="true" />
        {t('semantic.refresh')}
      </button>
    </header>
  );
}

function SemanticError({ message }: Readonly<{ message: string }>) {
  return (
    <div className="semantic-error" role="alert">
      <XCircle size={18} aria-hidden="true" />
      <span>{message}</span>
    </div>
  );
}

function SemanticContent({
  loading,
  overview,
  t,
}: Readonly<{ loading: boolean; overview: SemanticOverview | null; t: Translator }>) {
  if (loading && !overview) {
    return <output aria-live="polite">{t('semantic.status.loading')}</output>;
  }
  if (!overview) return null;
  return <SemanticOverviewContent overview={overview} t={t} />;
}

function SemanticOverviewContent({
  overview,
  t,
}: Readonly<{ overview: SemanticOverview; t: Translator }>) {
  const index = overview.index;
  const coverage = index && index.expected > 0
    ? `${integer(index.indexed)} / ${integer(index.expected)} (${percent(index.indexed / index.expected)})`
    : t('semantic.index.noData');
  const statusOk = overview.status === 'ok';
  return (
    <>
      <div className="semantic-admin-summary">
        <span className={`semantic-badge ${statusOk ? 'semantic-badge-success' : 'semantic-badge-warning'}`}>
          {statusOk ? <CheckCircle2 size={14} aria-hidden="true" /> : <XCircle size={14} aria-hidden="true" />}
          {statusOk ? t('semantic.status.ok') : t('semantic.status.degraded')}
        </span>
        <span>{overview.database ? t('semantic.status.databaseReady') : t('semantic.status.databaseUnavailable')}</span>
      </div>
      <div className="semantic-admin-grid">
        <StatusCard
          title={t('semantic.model.title')}
          value={overview.model?.version ?? t('semantic.model.missing')}
          detail={modelDetail(t, overview)}
        />
        <StatusCard
          title={t('semantic.search.title')}
          value={overview.searchReady ? t('semantic.status.searchReady') : t('semantic.status.searchUnavailable')}
          detail={overview.searchReady ? t('semantic.search.available') : t('semantic.search.fallback')}
          tone={overview.searchReady ? 'success' : 'warning'}
        />
        <StatusCard
          title={t('semantic.index.title')}
          value={coverage}
          detail={index?.complete ? t('semantic.index.complete') : t('semantic.index.incomplete')}
          tone={index?.complete ? 'success' : 'warning'}
        />
        <StatusCard
          title={t('semantic.indexer.title')}
          value={overview.indexer?.healthy ? t('semantic.indexer.healthy') : t('semantic.indexer.unhealthy')}
          detail={overview.indexer?.reason ?? t('semantic.indexer.missing')}
          tone={overview.indexer?.healthy ? 'success' : 'warning'}
        />
      </div>
    </>
  );
}

function StatusCard({
  title,
  value,
  detail,
  tone = 'neutral',
}: Readonly<{
  title: string;
  value: string;
  detail: string;
  tone?: 'neutral' | 'success' | 'warning';
}>) {
  return (
    <article className={`semantic-admin-card semantic-card-${tone}`}>
      <span>{title}</span>
      <strong>{value}</strong>
      <small>{detail}</small>
    </article>
  );
}

function BrainMark() {
  return <span className="semantic-admin-mark"><BrainCircuit size={24} aria-hidden="true" /></span>;
}

function modelDetail(t: Translator, overview: SemanticOverview): string {
  const model = overview.model;
  if (!model) return t('semantic.model.missing');
  const dimensions = model.dimensions ? `${model.dimensions} ${t('semantic.model.dimensions')}` : '';
  const artifact = model.artifactReady ? t('semantic.model.ready') : t('semantic.model.missing');
  return [dimensions, artifact].filter(Boolean).join(' · ');
}

function integer(value: number): string {
  return Math.round(value).toLocaleString('es-ES');
}

function percent(value: number): string {
  return value.toLocaleString('es-ES', { style: 'percent', maximumFractionDigits: 1 });
}

function errorMessage(t: Translator, cause: unknown): string {
  if (cause instanceof ApiRequestError) return t('semantic.error.load');
  return t('semantic.error.load');
}
