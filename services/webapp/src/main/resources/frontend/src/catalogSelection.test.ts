import { describe, expect, it, vi } from 'vitest';
import {
  inspectCatalogSelectionRefresh,
  isCatalogAppSelectable,
  validateCatalogSelection,
} from './catalogSelection';
import type { CatalogApp } from './types/catalog';

describe('isCatalogAppSelectable', () => {
  it('accepts only downloadable direct or fallback applications', () => {
    expect(isCatalogAppSelectable({
      ...app('direct', 'Directa'),
      resolutionStatus: 'direct',
      validationStatus: 'valid',
      downloadable: true,
    })).toBe(true);
    expect(isCatalogAppSelectable({
      ...app('fallback', 'Fallback'),
      resolutionStatus: 'fallback',
      validationStatus: 'valid',
      downloadable: true,
    })).toBe(true);
    expect(isCatalogAppSelectable({
      ...app('review', 'Revisión'),
      resolutionStatus: 'requires_manual_review',
      downloadable: true,
    })).toBe(false);
    expect(isCatalogAppSelectable({
      ...app('missing', 'Sin instalador'),
      resolutionStatus: 'direct',
      downloadable: false,
    })).toBe(false);
  });

  it('inspects only selections affected by the refreshed page', () => {
    const previousPage = [app('page-1', 'Página uno'), app('gone', 'Desaparece')];
    const nextPage = [{
      ...previousPage[0],
      resolutionStatus: 'requires_manual_review' as const,
      downloadable: false,
    }];

    expect(inspectCatalogSelectionRefresh(
      new Set(['page-1', 'gone', 'another-page']),
      previousPage,
      nextPage,
    )).toEqual({
      invalidIds: ['page-1'],
      missingIds: ['gone'],
    });
  });

  it('validates stale selections without treating transient errors as deletions', async () => {
    const loadApp = vi.fn(async (id: string) => {
      if (id === 'invalid') return app(id, 'Sin instalador');
      if (id === 'deleted') throw new Error('request_failed_404');
      if (id === 'temporary') throw new Error('request_failed_503');
      return {
        ...app(id, 'Disponible'),
        resolutionStatus: 'direct' as const,
        validationStatus: 'valid' as const,
        downloadable: true,
      };
    });

    await expect(validateCatalogSelection(
      ['valid', 'invalid', 'deleted', 'temporary', 'valid'],
      loadApp,
      2,
    )).resolves.toEqual({
      validIds: ['valid'],
      invalidIds: ['invalid', 'deleted'],
      unresolvedIds: ['temporary'],
    });
    expect(loadApp).toHaveBeenCalledTimes(4);
  });
});

function app(id: string, name: string): CatalogApp {
  return {
    id,
    slug: id,
    packageId: id,
    name,
    publisher: 'Publisher',
    description: null,
    longDescription: null,
    tags: [],
    operatingSystems: [],
    iconUrl: null,
    latestVersion: null,
    sourceLabel: 'No disponible',
    resolutionStatus: 'missing',
    validationStatus: 'unchecked',
    downloadable: false,
    updatedAt: '2026-07-08T00:00:00Z',
  };
}
