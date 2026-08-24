import { useTranslation } from '../../services/i18n';

/** Tabla administrativa genérica con estado vacío accesible. */
export function AdminTable({ title, rows }: { title: string; rows: string[][] }) {
  const t = useTranslation();
  return (
    <div className="admin-card admin-table-card">
      <h3>{title}</h3>
      <div className="admin-table">
        {rows.length ? rows.map((row, index) => (
          <div
            className="admin-table-row"
            key={`${title}-${index}`}
            style={{ gridTemplateColumns: `repeat(${row.length}, minmax(0, 1fr))` }}
          >
            {row.map((cell, cellIndex) => (
              <span key={`${title}-${index}-${cellIndex}`}>{cell}</span>
            ))}
          </div>
        )) : <p className="empty-state">{t('admin.table.empty')}</p>}
      </div>
    </div>
  );
}
