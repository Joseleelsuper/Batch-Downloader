import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useTranslation } from '../services/i18n';

interface Props {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

const pageSizes = [12, 24, 48];

export function Pagination({ page, pageSize, total, onPageChange, onPageSizeChange }: Props) {
  const t = useTranslation();
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const [draftPage, setDraftPage] = useState(String(page));
  const start = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const end = Math.min(total, page * pageSize);

  useEffect(() => {
    setDraftPage(String(page));
  }, [page]);

  function commitPage() {
    const parsed = draftPage === '' ? page : Number(draftPage);
    const next = Math.min(Math.max(Math.trunc(parsed), 1), pages);
    setDraftPage(String(next));
    if (next !== page) onPageChange(next);
  }

  return (
    <footer className="pagination">
      <span>
        {t('catalog.pagination.showing', {
          start,
          end,
          total: total.toLocaleString('es-ES'),
        })}
      </span>
      <div className="pagination-controls">
        <button
          aria-label={t('catalog.pagination.previous')}
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
          type="button"
        >
          <ChevronLeft size={18} />
        </button>
        <label className="page-input-label">
          <span>{t('catalog.pagination.page')}</span>
          <input
            value={draftPage}
            inputMode="numeric"
            onChange={(event) => setDraftPage(event.target.value.replace(/\D/g, ''))}
            onBlur={commitPage}
            onKeyDown={(event) => {
              if (event.key === 'Enter') commitPage();
            }}
            aria-label={t('catalog.pagination.page')}
          />
          <span>/ {pages}</span>
        </label>
        <button
          aria-label={t('catalog.pagination.next')}
          disabled={page >= pages}
          onClick={() => onPageChange(page + 1)}
          type="button"
        >
          <ChevronRight size={18} />
        </button>
      </div>
      <label className="page-size">
        <span className="sr-only">{t('catalog.perPage')}</span>
        <select
          aria-label={t('catalog.perPage')}
          value={pageSize}
          onChange={(event) => onPageSizeChange(Number(event.target.value))}
        >
          {pageSizes.map((size) => (
            <option key={size} value={size}>
              {t('catalog.pagination.perPage', { count: size })}
            </option>
          ))}
        </select>
      </label>
    </footer>
  );
}
