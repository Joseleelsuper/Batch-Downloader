import { useTranslation } from '../../services/i18n';
import type { ScraperQueueState } from '../../types/catalog';

/** Representa únicamente las colas públicas del pipeline vigente. */
export function ScraperQueues({ queues }: Readonly<{ queues: ScraperQueueState[] }>) {
  const t = useTranslation();
  const searcherFilter = queues.find((queue) => queue.queue === 'searcher_filter');
  const filterScraper = queues.find((queue) => queue.queue === 'filter_scraper');
  const scraperSoFilter = queues.find((queue) => queue.queue === 'scraper_so_filter');
  const soFilterDescriptor = queues.find((queue) => queue.queue === 'so_filter_descriptor');
  return (
    <div className="scraper-pipeline admin-card">
      <PipelineStage title={t('admin.scraper.stage.searcher')} count={searcherFilter?.queued ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.searcherFilter')} queue={searcherFilter} />
      <PipelineStage title={t('admin.scraper.stage.filter')} count={filterScraper?.queued ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.filterScraper')} queue={filterScraper} />
      <PipelineStage title={t('admin.scraper.stage.scraper')} count={filterScraper?.inProgress ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.scraperSoFilter')} queue={scraperSoFilter} />
      <PipelineStage title={t('admin.scraper.stage.soFilter')} count={scraperSoFilter?.inProgress ?? 0} />
      <QueueColumn title={t('admin.scraper.queue.soFilterDescriptor')} queue={soFilterDescriptor} />
      <PipelineStage title={t('admin.scraper.stage.descriptor')} count={soFilterDescriptor?.inProgress ?? 0} />
    </div>
  );
}

function PipelineStage({ title, count }: Readonly<{ title: string; count: number }>) {
  return (
    <div className="pipeline-stage">
      <strong>{title}</strong>
      <span>{count}</span>
    </div>
  );
}

function QueueColumn({ title, queue }: Readonly<{ title: string; queue?: ScraperQueueState }>) {
  const t = useTranslation();
  const items = queue?.items ?? [];
  return (
    <div className="pipeline-queue">
      <div className="pipeline-queue-heading">
        <strong>{title}</strong>
        <span>{queue ? `${queue.queued}/${queue.inProgress}` : '0/0'}</span>
      </div>
      <div className="pipeline-queue-list">
        {items.length ? items.slice(0, 5).map((item) => (
          <span className={`pipeline-token pipeline-token-${item.status}`} key={item.id}>
            {item.appName || item.packageId}
          </span>
        )) : <span className="pipeline-token">{t('admin.table.empty')}</span>}
      </div>
    </div>
  );
}
