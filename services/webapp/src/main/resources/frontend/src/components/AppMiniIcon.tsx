import type { CatalogApp } from '../types/catalog';

export function AppMiniIcon({ app }: { app: CatalogApp }) {
  if (app.iconUrl) {
    return <img className="mini-icon" src={app.iconUrl} alt="" loading="lazy" />;
  }
  return (
    <span className="mini-icon mini-icon-fallback">
      {app.name.slice(0, 1).toUpperCase()}
    </span>
  );
}
