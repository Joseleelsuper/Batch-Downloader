import { describe, expect, it } from 'vitest';
import { mergeSelectedAppIntoPage } from './catalogSelection';
import type { CatalogApp } from './types/catalog';

const selected = app('selected', 'GOG GALAXY');
const first = app('first', 'BMDesk');
const second = app('second', 'hyperdu');

describe('mergeSelectedAppIntoPage', () => {
  it('keeps the current page when the selected app is already visible', () => {
    const apps = [first, selected, second];

    expect(mergeSelectedAppIntoPage(apps, selected, 12)).toBe(apps);
  });

  it('pins the selected app when a detail route points outside the loaded page', () => {
    expect(mergeSelectedAppIntoPage([first, second], selected, 2)).toEqual([selected, first]);
  });

  it('does not change the page without a selected app', () => {
    const apps = [first, second];

    expect(mergeSelectedAppIntoPage(apps, null, 12)).toBe(apps);
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
