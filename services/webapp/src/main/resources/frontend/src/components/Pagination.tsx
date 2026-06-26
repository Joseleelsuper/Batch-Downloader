import { ChevronLeft, ChevronRight } from 'lucide-react';
import { t } from '../services/i18n';

interface Props {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
}

export function Pagination({ page, pageSize, total, onPageChange }: Props) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const start = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const end = Math.min(total, page * pageSize);
  return (
    <footer className="pagination">
      <span>
        {t('catalog.showing')} {start} a {end} de {total.toLocaleString('es-ES')}{' '}
        {t('catalog.results')}
      </span>
      <div className="pagination-controls">
        <button disabled={page <= 1} onClick={() => onPageChange(page - 1)} type="button">
          <ChevronLeft size={18} />
        </button>
        <strong>{page}</strong>
        <span>...</span>
        <span>{pages}</span>
        <button disabled={page >= pages} onClick={() => onPageChange(page + 1)} type="button">
          <ChevronRight size={18} />
        </button>
      </div>
      <button className="page-size" type="button">
        {t('catalog.perPage')}
      </button>
    </footer>
  );
}
