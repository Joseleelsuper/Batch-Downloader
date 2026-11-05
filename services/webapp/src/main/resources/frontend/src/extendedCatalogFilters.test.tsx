import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { AppFilters } from './components/AppFilters';
import { catalogFiltersToSearchParams, nextFilters, parseCatalogFilters } from './catalogFilters';
import { bundleFiltersToSearch, parseBundleFilters } from './bundleCatalogFilters';
import { bundleSearch } from './api/bundles';

afterEach(cleanup);

it('conserva arquitectura y mínimo en los enlaces, y reajusta el mínimo al retirar etiquetas', () => {
  const filters = parseCatalogFilters('tag=video&tag=tools&tag=tools&minimumTags=1&architecture=arm64');
  expect(parseCatalogFilters(catalogFiltersToSearchParams(filters))).toEqual(filters);
  expect(filters.minimumTags).toBe(1);
  expect(parseCatalogFilters('tag=tools&minimumTags=2').minimumTags).toBeUndefined();
  expect(nextFilters({ ...filters, minimumTags: 2 }, { tags: ['video'] }).minimumTags).toBeUndefined();
});

it('expone controles con etiquetas accesibles para arquitectura y coincidencia mínima', () => {
  const architecture = vi.fn(), minimum = vi.fn();
  render(<MemoryRouter><AppFilters active="all" onChange={vi.fn()} selectedTagCount={3}
    architecture="x64" onArchitectureChange={architecture} onMinimumTagsChange={minimum} /></MemoryRouter>);
  fireEvent.change(screen.getByRole('combobox', { name: 'Arquitectura' }), { target: { value: 'arm64' } });
  fireEvent.change(screen.getByRole('combobox', { name: 'Coincidencia de etiquetas' }), { target: { value: '2' } });
  expect(architecture).toHaveBeenCalledWith('arm64');
  expect(minimum).toHaveBeenCalledWith(2);
});

it('conserva ambos sentidos de orden en los enlaces y la petición del catálogo de bundles', () => {
  for (const direction of ['asc', 'desc']) {
    const filters = parseBundleFilters(new URLSearchParams(`sort=stars&direction=${direction}`));
    expect(parseBundleFilters(bundleFiltersToSearch(filters))).toEqual(filters);
    expect(bundleSearch(filters).get('direction')).toBe(direction);
  }
  expect(parseBundleFilters(new URLSearchParams('direction=invalid')).direction).toBeUndefined();
});
