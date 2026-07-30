import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppFilters } from './AppFilters';
import type { CatalogApp, FilterKey } from '../types/catalog';

const counts: Record<FilterKey, number> = {
  all: 10,
  available: 8,
  review: 1,
  missing: 1,
};

function selectedApp(index: number): CatalogApp {
  return {
    id: `app-${index}`,
    slug: `app-${index}`,
    packageId: `package-${index}`,
    name: `App ${index}`,
    tags: [],
    operatingSystems: ['windows'],
    iconUrl: `https://icons.example/app-${index}.png`,
    sourceLabel: 'Directa',
    resolutionStatus: 'direct',
    validationStatus: 'valid',
    downloadable: true,
    updatedAt: '2026-07-30T00:00:00Z',
  };
}

describe('AppFilters', () => {
  afterEach(() => cleanup());

  it('shows only the four public catalog filters', () => {
    render(
      <MemoryRouter>
        <AppFilters active="all" counts={counts} onChange={vi.fn()} />
      </MemoryRouter>,
    );

    expect(screen.getAllByRole('button').filter((button) => button.classList.contains('filter-item'))).toHaveLength(4);
    expect(screen.queryByText('Pendientes')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Todas/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('renders facet links and active facet chips', () => {
    const onRemoveTag = vi.fn();
    const onRemovePublisher = vi.fn();
    render(
      <MemoryRouter>
        <AppFilters
          active="all"
          counts={counts}
          selectedTags={['.NET', 'runtime']}
          selectedPublishers={['ACME, Inc.']}
          tagMatchMin={1}
          catalogSearch="tag=.NET&publisher=ACME%2C+Inc."
          onChange={vi.fn()}
          onRemoveTag={onRemoveTag}
          onRemovePublisher={onRemovePublisher}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /Tags/ })).toHaveAttribute(
      'href',
      '/catalog/tags?tag=.NET&publisher=ACME%2C+Inc.',
    );
    expect(screen.getByText('1 de 2 tags')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '.NET' }));
    fireEvent.click(screen.getByRole('button', { name: 'ACME, Inc.' }));

    expect(onRemoveTag).toHaveBeenCalledWith('.NET');
    expect(onRemovePublisher).toHaveBeenCalledWith('ACME, Inc.');
  });

  it('exposes platform toggle state and lets the last active platform restore the other two', () => {
    const onToggleOperatingSystem = vi.fn();
    render(
      <MemoryRouter>
        <AppFilters
          active="all"
          counts={counts}
          onChange={vi.fn()}
          operatingSystems={['windows']}
          onToggleOperatingSystem={onToggleOperatingSystem}
        />
      </MemoryRouter>,
    );

    const windows = screen.getByRole('button', { name: 'Windows' });
    expect(windows).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(windows);

    expect(onToggleOperatingSystem).toHaveBeenCalledTimes(1);
    expect(onToggleOperatingSystem).toHaveBeenCalledWith('windows');
  });

  it('shows the 17 newest selected apps followed by the hidden count and removes an app from its icon', () => {
    const onRemoveSelected = vi.fn();
    const selectedApps = Array.from({ length: 20 }, (_, index) => selectedApp(index + 1));
    const { container } = render(
      <MemoryRouter>
        <AppFilters
          active="all"
          counts={counts}
          onChange={vi.fn()}
          selectedApps={selectedApps}
          onRemoveSelected={onRemoveSelected}
        />
      </MemoryRouter>,
    );

    const appButtons = container.querySelectorAll('.selection-app-button');
    expect(appButtons).toHaveLength(17);
    expect(appButtons[0]).toHaveAttribute('title', 'App 20');
    expect(appButtons[16]).toHaveAttribute('title', 'App 4');
    expect(screen.getByText('+3')).toHaveAttribute(
      'aria-label',
      '3 aplicaciones seleccionadas más',
    );
    expect(screen.getByText('20/100')).toBeInTheDocument();
    expect(screen.queryByText('Descarga seleccionada')).not.toBeInTheDocument();

    fireEvent.click(appButtons[0]);

    expect(onRemoveSelected).toHaveBeenCalledWith('app-20');
  });
});
