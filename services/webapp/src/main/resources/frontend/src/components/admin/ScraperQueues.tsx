import { useTranslation } from '../../services/i18n';
import type { ScraperQueueState } from '../../types/catalog';

/** Representa únicamente las colas públicas del pipeline vigente. */
export function ScraperQueues({ queues }: Readonly<{ queues: ScraperQueueState[] }>) {
  const t = useTranslation();
  const searcherFilter = queues.find((queue) => queue.queue === 'searcher_filter');
  const filterScraper = queues.find((queue) => queue.queue === 'filter_scraper');
  const scraperSoFilter = queues.find((queue) => queue.queue === 'scraper_so_filter');
  const soFilterDescriptor = queues.find((queue) => queue.queue === 'so_filter_descriptor');
  const stages = [
    [t('admin.scraper.stage.searcher'), searcherFilter?.queued ?? 0],
    [t('admin.scraper.stage.filter'), filterScraper?.queued ?? 0],
    [t('admin.scraper.stage.scraper'), filterScraper?.inProgress ?? 0],
    [t('admin.scraper.stage.soFilter'), scraperSoFilter?.inProgress ?? 0],
    [t('admin.scraper.stage.descriptor'), soFilterDescriptor?.inProgress ?? 0],
  ] as const;
  return (
    <div className="scraper-pipeline admin-card">
      <div className="pipeline-stages">
        {stages.map(([title, count]) => <PipelineStage key={title} title={title} count={count} />)}
      </div>
      <div className="pipeline-queues">
        <QueueColumn title={t('admin.scraper.queue.searcherFilter')} queue={searcherFilter} />
        <QueueColumn title={t('admin.scraper.queue.filterScraper')} queue={filterScraper} />
        <QueueColumn title={t('admin.scraper.queue.scraperSoFilter')} queue={scraperSoFilter} />
        <QueueColumn title={t('admin.scraper.queue.soFilterDescriptor')} queue={soFilterDescriptor} />
      </div>
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
