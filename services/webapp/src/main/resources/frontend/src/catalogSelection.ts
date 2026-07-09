import type { CatalogApp } from './types/catalog';

export function mergeSelectedAppIntoPage(
  apps: CatalogApp[],
  selected: CatalogApp | null | undefined,
  pageSize: number,
): CatalogApp[] {
  if (!selected || apps.some((app) => app.id === selected.id)) {
    return apps;
  }
  return [selected, ...apps].slice(0, Math.max(1, pageSize));
}
