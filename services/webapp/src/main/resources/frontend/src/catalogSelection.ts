import type { CatalogApp } from './types/catalog';

const DEFAULT_SELECTION_VALIDATION_CONCURRENCY = 6;

export function isCatalogAppSelectable(app: CatalogApp): boolean {
  return app.downloadable && (app.resolutionStatus === 'direct' || app.resolutionStatus === 'fallback');
}

export interface CatalogSelectionRefreshInspection {
  invalidIds: string[];
  missingIds: string[];
}

export function inspectCatalogSelectionRefresh(
  selectedIds: ReadonlySet<string>,
  previousApps: readonly CatalogApp[],
  nextApps: readonly CatalogApp[],
): CatalogSelectionRefreshInspection {
  const previousIds = new Set(previousApps.map((app) => app.id));
  const nextById = new Map(nextApps.map((app) => [app.id, app]));
  const invalidIds = nextApps
    .filter((app) => selectedIds.has(app.id) && !isCatalogAppSelectable(app))
    .map((app) => app.id);
  const missingIds = Array.from(selectedIds)
    .filter((id) => previousIds.has(id) && !nextById.has(id));
  return { invalidIds, missingIds };
}

export interface CatalogSelectionValidation {
  validIds: string[];
  invalidIds: string[];
  unresolvedIds: string[];
}

export async function validateCatalogSelection(
  ids: readonly string[],
  loadApp: (id: string) => Promise<CatalogApp>,
  concurrency = DEFAULT_SELECTION_VALIDATION_CONCURRENCY,
): Promise<CatalogSelectionValidation> {
  const uniqueIds = Array.from(new Set(ids));
  const results = new Array<'valid' | 'invalid' | 'unresolved'>(uniqueIds.length);
  let cursor = 0;

  async function validateNext(): Promise<void> {
    while (cursor < uniqueIds.length) {
      const index = cursor;
      cursor += 1;
      try {
        const app = await loadApp(uniqueIds[index]);
        results[index] = isCatalogAppSelectable(app) ? 'valid' : 'invalid';
      } catch (error) {
        results[index] = isMissingCatalogAppError(error) ? 'invalid' : 'unresolved';
      }
    }
  }

  const workerCount = Math.min(uniqueIds.length, Math.max(1, Math.trunc(concurrency)));
  await Promise.all(Array.from({ length: workerCount }, () => validateNext()));

  return uniqueIds.reduce<CatalogSelectionValidation>((validation, id, index) => {
    const result = results[index];
    if (result === 'valid') validation.validIds.push(id);
    else if (result === 'invalid') validation.invalidIds.push(id);
    else validation.unresolvedIds.push(id);
    return validation;
  }, { validIds: [], invalidIds: [], unresolvedIds: [] });
}

function isMissingCatalogAppError(error: unknown): boolean {
  return error instanceof Error && /^request_failed_(404|410)$/.test(error.message);
}
