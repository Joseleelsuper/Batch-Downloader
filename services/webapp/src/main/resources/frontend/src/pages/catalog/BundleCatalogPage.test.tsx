import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import * as api from '../../api/bundles';
import { BundleCatalogPage, BundleFacetDirectoryPage, CatalogEntry } from './BundleCatalogPage';

vi.mock('./CatalogPages', () => ({ CatalogPage: () => <p>Apps</p> }));
vi.mock('../../auth/AuthContext', () => ({ useAuth: () => ({ account: null, status: 'anonymous' }) }));
vi.mock('../../components/BundleCard', () => ({ BundleCard: ({ bundle }: { bundle: { name: string } }) => <article>{bundle.name}</article> }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function Location() { const location = useLocation(); return <output>{location.pathname}{location.search}</output>; }

it('redirige el enlace antiguo a la página de bundles conservando tipo y orden', async () => {
  render(<MemoryRouter initialEntries={['/catalog?bundleType=official&sort=stars']}><Routes>
    <Route path="/catalog" element={<CatalogEntry />} /><Route path="/catalog/bundles" element={<Location />} />
  </Routes></MemoryRouter>);
  expect(await screen.findByText(/\/catalog\/bundles\?/)).toHaveTextContent('bundleType=official');
  expect(screen.getByRole('status')).toHaveTextContent('sort=stars');
});

it('pagina desde el servidor y reinicia la página al cambiar de tipo', async () => {
  const fetch = vi.spyOn(api, 'fetchBundles').mockResolvedValue({ data: [], page: 2, pageSize: 12, total: 18,
    counts: { all: 30, public: 12, official: 18 } });
  render(<MemoryRouter initialEntries={['/catalog/bundles?bundleType=official&sort=stars&page=2']}>
    <BundleCatalogPage /><Location />
  </MemoryRouter>);
  await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.objectContaining({ type: 'official', sort: 'stars', page: 2 }), expect.any(AbortSignal)));
  expect(await screen.findByRole('link', { name: 'Página anterior' })).toHaveAttribute('href', expect.stringContaining('bundleType=official'));
  fireEvent.click(screen.getByRole('button', { name: /Públicos/ }));
  await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'public', page: 1 }), expect.any(AbortSignal)));
});

it('abre una letra con resultados aunque no exista ninguna etiqueta que empiece por A', async () => {
  vi.spyOn(api, 'fetchBundleFacets').mockResolvedValue({ tags: [{ value: 'trabajo', label: 'Trabajo', letter: 'T', count: 6 }], creators: [] });
  render(<MemoryRouter initialEntries={['/catalog/bundles/tags']}><BundleFacetDirectoryPage kind="tags" /></MemoryRouter>);
  expect(await screen.findByRole('button', { name: 'Trabajo 6' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'T' })).toHaveAttribute('aria-pressed', 'true');
});
