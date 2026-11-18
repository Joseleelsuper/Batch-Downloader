import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { AppFilters } from './components/AppFilters';
import { catalogFiltersToSearchParams, nextFilters, parseCatalogFilters } from './catalogFilters';

afterEach(cleanup);

it('ofrece las tres arquitecturas y permite volver a cualquiera con un control accesible', () => {
  const onArchitectureChange = vi.fn();
  render(<MemoryRouter><AppFilters active="all" onChange={vi.fn()}
    architecture="x64" onArchitectureChange={onArchitectureChange} /></MemoryRouter>);
  const architectures = within(screen.getByRole('group', { name: 'Arquitectura' }));
  expect(architectures.getAllByRole('button').map((button) => button.textContent))
    .toEqual(['Cualquiera', 'x64', 'x86', 'arm64']);
  expect(architectures.getByRole('button', { name: 'x64' })).toHaveAttribute('aria-pressed', 'true');
  fireEvent.click(architectures.getByRole('button', { name: 'arm64' }));
  expect(onArchitectureChange).toHaveBeenLastCalledWith('arm64');
  fireEvent.click(architectures.getByRole('button', { name: 'Cualquiera' }));
  expect(onArchitectureChange).toHaveBeenLastCalledWith(undefined);
});

it('conserva los demás filtros al cambiar arquitectura y reinicia la página', () => {
  const original = parseCatalogFilters('query=editor&status=all&tag=tools&publisher=ACME&os=linux&page=4');
  const changed = nextFilters(original, { architecture: 'arm64' });
  expect(parseCatalogFilters(catalogFiltersToSearchParams(changed)))
    .toEqual({ ...original, architecture: 'arm64', page: 1 });
  const cleared = nextFilters(changed, { architecture: undefined });
  expect(catalogFiltersToSearchParams(cleared).has('architecture')).toBe(false);
});
