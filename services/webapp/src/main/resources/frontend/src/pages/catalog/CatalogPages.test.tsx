import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as catalogApi from '../../api/catalogApps';
import { CatalogPage, clearCatalogPageCache } from './CatalogPages';

vi.mock('../../hooks/useDownloadJob', () => ({ useDownloadJob: () => ({ starting: false }) }));

function LocationProbe() {
  return <output data-testid="location">{useLocation().search}</output>;
}

function swipe(target: Element, from: number, to: number) {
  fireEvent.touchStart(target, { touches: [{ identifier: 1, clientX: from, clientY: 100 }] });
  fireEvent.touchEnd(target, { touches: [], changedTouches: [{ identifier: 1, clientX: to, clientY: 100 }] });
}

describe('catalog swipe navigation', () => {
  beforeEach(() => {
    clearCatalogPageCache();
    vi.stubGlobal('localStorage', { getItem: () => null, setItem: vi.fn() });
    vi.spyOn(catalogApi, 'fetchApps').mockImplementation(async ({ page }) => ({
      data: [], page, pageSize: 12, total: 36,
    }));
    vi.spyOn(catalogApi, 'fetchCatalogStats').mockResolvedValue({
      total: 36, filters: { all: 36, available: 36, review: 0, missing: 0 }, generatedAt: '',
    });
    vi.spyOn(catalogApi, 'fetchCatalogFacets').mockResolvedValue({ tags: [], publishers: [] });
    vi.spyOn(catalogApi, 'connectCatalogEvents').mockReturnValue(() => undefined);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it.each([true, false])('limits swipes to the app layout and mobile mode (%s)', async (compact) => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: compact, addEventListener: vi.fn(), removeEventListener: vi.fn(),
    })));
    const { container } = render(<MemoryRouter initialEntries={[
      '/catalog?status=review&searchMode=lexical&query=editor&page=2',
    ]}><CatalogPage /><LocationProbe /></MemoryRouter>);
    await waitFor(() => expect(container.querySelector('.table-card')).toHaveAttribute('aria-busy', 'false'));
    const params = () => new URLSearchParams(screen.getByTestId('location').textContent ?? '');
    swipe(container.querySelector('.catalog-panel')!, 220, 80);
    expect(params().get('page')).toBe('2');

    const apps = container.querySelector('.table-card')!;
    swipe(apps, 220, 80);
    await waitFor(() => expect(params().get('page')).toBe(compact ? '3' : '2'));
    expect(params().get('query')).toBe('editor');
    expect(params().get('status')).toBe('review');
    expect(params().get('searchMode')).toBe('lexical');
    if (!compact) return;

    await waitFor(() => expect(apps).toHaveAttribute('aria-busy', 'false'));
    swipe(apps, 220, 80);
    expect(params().get('page')).toBe('3');
    swipe(apps, 80, 220);
    await waitFor(() => expect(params().get('page')).toBe('2'));
  });
});
