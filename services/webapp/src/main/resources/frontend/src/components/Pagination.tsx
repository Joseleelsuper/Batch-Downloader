import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useState } from 'react';
import { t } from '../services/i18n';

interface Props {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}

const pageSizes = [12, 24, 48];

export function Pagination({ page, pageSize, total, onPageChange, onPageSizeChange }: Props) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const [draftPage, setDraftPage] = useState(String(page));
  const start = total === 0 ? 0 : (page - 1) * pageSize + 1;
  const end = Math.min(total, page * pageSize);

  useEffect(() => {
    setDraftPage(String(page));
  }, [page]);

  function commitPage() {
    const parsed = Number(draftPage);
    const next = Number.isFinite(parsed) ? Math.min(Math.max(Math.trunc(parsed), 1), pages) : page;
    setDraftPage(String(next));
    if (next !== page) onPageChange(next);
  }

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
        <label className="page-input-label">
          <span>Pagina</span>
          <input
            value={draftPage}
            inputMode="numeric"
            onChange={(event) => setDraftPage(event.target.value.replace(/\D/g, ''))}
            onBlur={commitPage}
            onKeyDown={(event) => {
              if (event.key === 'Enter') commitPage();
            }}
            aria-label="Pagina"
          />
          <span>/ {pages}</span>
        </label>
        <button disabled={page >= pages} onClick={() => onPageChange(page + 1)} type="button">
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
              {size} / pagina
            </option>
          ))}
        </select>
      </label>
    </footer>
  );
}
