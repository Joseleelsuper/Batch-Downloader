import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as adminApi from '../api/adminApps';
import * as catalogApi from '../api/catalogApps';
import type { CatalogApp, CatalogResponse } from '../types/catalog';
import { BundleAppPicker } from './BundleAppPicker';

const app: CatalogApp = {
  id: 'steam', slug: 'steam', packageId: 'Valve.Steam', name: 'Steam', publisher: 'Valve',
  tags: [], operatingSystems: ['windows'], sourceLabel: 'Official', resolutionStatus: 'direct',
  validationStatus: 'valid', downloadable: true, updatedAt: '2026-10-03T00:00:00Z',
};
const epic = { ...app, id: 'epic', name: 'Epic Games', packageId: 'Epic.Games', publisher: 'Epic' };
const page = (apps: CatalogApp[], total = apps.length): CatalogResponse => ({ data: apps, total, page: 1, pageSize: 20 });

function Picker({ initial = [], administrator = false }: { initial?: CatalogApp[]; administrator?: boolean }) {
  const [apps, setApps] = useState(initial);
  return <><BundleAppPicker apps={apps} onChange={setApps} administrator={administrator} />
    <output data-testid="selection">{apps.map((item) => item.id).join(',')}</output></>;
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

describe('BundleAppPicker', () => {
  it('agrupa pulsaciones durante 180 ms antes de buscar', async () => {
    vi.useFakeTimers();
    const fetch = vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue(page([app]));
    render(<Picker />);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'S' } });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Steam' } });
    await act(async () => { vi.advanceTimersByTime(179); });
    expect(fetch).not.toHaveBeenCalled();
    await act(async () => { vi.advanceTimersByTime(1); });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith(expect.objectContaining({ query: 'Steam' }), expect.any(AbortSignal));
  });

  it('busca por relevancia, pagina y conserva la selección al cambiar la consulta', async () => {
    const fetch = vi.spyOn(catalogApi, 'fetchApps').mockImplementation(async (params) =>
      params.query ? page([epic]) : params.page === 2 ? page([epic], 21) : page([app], 21));
    render(<Picker />);
    fireEvent.click(await screen.findByRole('button', { name: 'Añadir Steam' }));
    expect(screen.getByRole('button', { name: 'Steam ya está en el bundle' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Página siguiente' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Añadir Epic Games' }));
    expect(fetch).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, pageSize: 20, sort: 'relevance', searchMode: 'lexical' }), expect.any(AbortSignal));
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Epic' } });
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(expect.objectContaining({ query: 'Epic', page: 1 }), expect.any(AbortSignal)));
    expect(screen.getByTestId('selection')).toHaveTextContent('steam,epic');
    fireEvent.click(screen.getByRole('button', { name: 'Subir Epic Games' }));
    expect(screen.getByTestId('selection')).toHaveTextContent('epic,steam');
    fireEvent.click(screen.getByRole('button', { name: 'Quitar Steam' }));
    expect(screen.getByTestId('selection')).toHaveTextContent(/^epic$/);
  });

  it('descarta respuestas antiguas aunque el cliente ignore abort y permite reintentar errores', async () => {
    let resolveOld!: (value: CatalogResponse) => void;
    const fetch = vi.spyOn(catalogApi, 'fetchApps')
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(page([epic]));
    render(<Picker />);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Epic' } });
    expect(fetch.mock.calls[0][1]?.aborted).toBe(true);
    expect(await screen.findByText('No se pudieron cargar las aplicaciones. Vuelve a intentarlo.')).toBeInTheDocument();
    await act(async () => resolveOld(page([app])));
    expect(screen.queryByRole('button', { name: 'Añadir Steam' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('button', { name: 'Añadir Epic Games' })).toBeInTheDocument();
  });

  it('conserva el foco de teclado al reordenar y elige un destino al quitar una fila', async () => {
    vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue(page([]));
    const third = { ...app, id: 'gog', name: 'GOG' };
    render(<Picker initial={[app, epic, third]} />);
    const activate = (name: string) => {
      const button = screen.getByRole('button', { name });
      button.focus();
      fireEvent.click(button, { detail: 0 });
    };

    activate('Subir GOG');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Subir GOG' })).toHaveFocus());
    expect(screen.getByTestId('selection')).toHaveTextContent('steam,gog,epic');
    activate('Subir GOG');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Bajar GOG' })).toHaveFocus());
    activate('Quitar Steam');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Quitar Epic Games' })).toHaveFocus());
    activate('Quitar Epic Games');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Quitar GOG' })).toHaveFocus());
    activate('Quitar GOG');
    await waitFor(() => expect(screen.getByRole('searchbox')).toHaveFocus());
  });

  it('aplica el máximo de 100 y utiliza el catálogo administrativo completo', async () => {
    const fetch = vi.spyOn(adminApi, 'fetchAdminApps').mockResolvedValue(page([app]));
    const initial = Array.from({ length: 100 }, (_, index) => ({ ...epic, id: String(index), name: `App ${index}` }));
    render(<Picker initial={initial} administrator />);
    expect(await screen.findByRole('button', { name: 'Añadir Steam' })).toBeDisabled();
    expect(fetch).toHaveBeenCalledWith({ query: '', filter: 'all', sort: 'relevance', page: 1, pageSize: 20 }, expect.any(AbortSignal));
    fireEvent.click(screen.getByRole('button', { name: 'Quitar App 0' }));
    expect(screen.getByRole('button', { name: 'Añadir Steam' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Añadir Steam' }));
    const selection = screen.getByRole('region', { name: 'Tu selección' });
    expect(within(selection).getAllByRole('listitem')).toHaveLength(100);
    expect(within(selection).getByText('Steam')).toBeInTheDocument();
  });

  it('distingue una búsqueda vacía del fallo y no envía el formulario al pulsar Enter', async () => {
    vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue(page([]));
    const submit = vi.fn((event) => event.preventDefault());
    render(<form onSubmit={submit}><Picker /><button type="submit">Guardar</button></form>);
    expect(await screen.findByText('No hay resultados. Prueba con otro nombre, editor o etiqueta.')).toBeInTheDocument();
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    screen.getByRole('searchbox').dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(submit).not.toHaveBeenCalled();
  });
});
