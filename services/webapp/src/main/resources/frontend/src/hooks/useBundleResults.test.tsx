import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import * as api from '../api/bundles';
import type { BundleResponse, BundleSummary } from '../types/catalog';
import { useBundleResults } from './useBundleResults';

const auth = vi.hoisted(() => ({ account: { id: 'first' }, status: 'authenticated' }));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); auth.account = { id: 'first' }; });

function page(name: string): BundleResponse {
  return { data: [{ id: name, name } as BundleSummary], page: 1, pageSize: 12, total: 1 };
}

it('aborta e ignora una respuesta sustituida al cambiar los filtros', async () => {
  let resolveOld!: (value: BundleResponse) => void;
  const fetch = vi.spyOn(api, 'fetchBundles').mockImplementation(({ query }) => query === 'old'
    ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(page('current')));
  const { result, rerender } = renderHook(({ query }) => useBundleResults({ query }), {
    initialProps: { query: 'old' },
  });
  const oldSignal = fetch.mock.calls[0][1];
  rerender({ query: 'current' });
  await waitFor(() => expect(result.current.response?.data[0].name).toBe('current'));
  await act(async () => { resolveOld(page('old')); });
  expect(oldSignal?.aborted).toBe(true);
  expect(result.current.response?.data[0].name).toBe('current');
});

it('oculta inmediatamente los votos de otra cuenta incluso con caché activada', async () => {
  let resolveNext!: (value: BundleResponse) => void;
  const fetch = vi.spyOn(api, 'fetchBundles').mockResolvedValue(page('first-account'));
  const { result, rerender } = renderHook(() => useBundleResults({ query: 'account-scope' }, true));
  await waitFor(() => expect(result.current.response?.data[0].name).toBe('first-account'));
  fetch.mockImplementation(() => new Promise((resolve) => { resolveNext = resolve; }));
  auth.account = { id: 'second' };
  rerender();
  expect(result.current.response).toBeNull();
  await act(async () => { resolveNext(page('second-account')); });
  expect(result.current.response?.data[0].name).toBe('second-account');
});

it('identifica los resultados antiguos y permite refrescar después de una estrella', async () => {
  let resolveNext!: (value: BundleResponse) => void;
  const fetch = vi.spyOn(api, 'fetchBundles').mockResolvedValue(page('initial'));
  const { result, rerender } = renderHook(({ query }) => useBundleResults({ query }), {
    initialProps: { query: 'initial' },
  });
  await waitFor(() => expect(result.current.isCurrent).toBe(true));
  fetch.mockImplementationOnce(() => new Promise((resolve) => { resolveNext = resolve; }));
  rerender({ query: 'new' });
  expect(result.current.isCurrent).toBe(false);
  await act(async () => { resolveNext(page('new')); });
  expect(result.current.isCurrent).toBe(true);
  act(() => result.current.refresh());
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
});
