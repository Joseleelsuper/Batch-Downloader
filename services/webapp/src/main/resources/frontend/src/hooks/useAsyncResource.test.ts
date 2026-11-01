import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useAsyncResource } from './useAsyncResource';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

afterEach(() => { cleanup(); vi.useRealTimers(); });

it('conserva datos válidos ante un fallo y permite reintentar el recurso', async () => {
  const load = vi.fn().mockResolvedValueOnce(['first']).mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce(['updated']);
  const { result } = renderHook(() => useAsyncResource<string[]>(load));
  expect(result.current.loading).toBe(true);
  await waitFor(() => expect(result.current.data).toEqual(['first']));
  act(() => result.current.refresh());
  await waitFor(() => expect(result.current.error).toBeInstanceOf(Error));
  expect(result.current.data).toEqual(['first']);
  expect(result.current.loading).toBe(false);
  act(() => result.current.refresh());
  await waitFor(() => expect(result.current.data).toEqual(['updated']));
  expect(result.current.error).toBeUndefined();
});

it('cancela y descarta respuestas de otra cuenta aunque el transporte ignore la cancelación', async () => {
  const first = deferred<string>();
  const second = deferred<string>();
  const signals: AbortSignal[] = [];
  const load = vi.fn((scope: string, signal: AbortSignal) => {
    signals.push(signal);
    return scope === 'first' ? first.promise : second.promise;
  });
  const { result, rerender, unmount } = renderHook(({ scope }) => useAsyncResource(
    (signal) => load(scope, signal), { scope },
  ), { initialProps: { scope: 'first' } });
  await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
  rerender({ scope: 'second' });
  expect(signals[0].aborted).toBe(true);
  expect(result.current.data).toBeUndefined();
  await act(async () => first.resolve('private first result'));
  await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  expect(result.current.data).toBeUndefined();
  await act(async () => second.resolve('second result'));
  expect(result.current.data).toBe('second result');
  unmount();
  expect(signals[1].aborted).toBe(true);
});

it('retira los datos anteriores inmediatamente al cambiar de ámbito', async () => {
  const pending = deferred<string>();
  const { result, rerender } = renderHook(({ scope }) => useAsyncResource(
    () => scope === 'first' ? Promise.resolve('first data') : pending.promise, { scope },
  ), { initialProps: { scope: 'first' } });
  await waitFor(() => expect(result.current.data).toBe('first data'));
  rerender({ scope: 'second' });
  expect(result.current.data).toBeUndefined();
  expect(result.current.loading).toBe(true);
});

it('acepta null como respuesta completada y no consulta recursos desactivados', async () => {
  const load = vi.fn().mockResolvedValue(null);
  const { result, rerender } = renderHook(({ enabled }) => useAsyncResource(load, { enabled }), {
    initialProps: { enabled: false },
  });
  expect(load).not.toHaveBeenCalled();
  expect(result.current.loading).toBe(false);
  rerender({ enabled: true });
  await waitFor(() => expect(result.current.loading).toBe(false));
  expect(result.current.data).toBeNull();
  expect(load).toHaveBeenCalledTimes(1);
});

it('refresca cada diez segundos sin solapar peticiones ni continuar después de desmontar', async () => {
  vi.useFakeTimers();
  const pending = deferred<number>();
  const load = vi.fn(() => pending.promise);
  const { unmount } = renderHook(() => useAsyncResource(load, { pollMs: 10000 }));
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(load).toHaveBeenCalledTimes(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
  expect(load).toHaveBeenCalledTimes(1);
  await act(async () => pending.resolve(1));
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(load).toHaveBeenCalledTimes(2);
  unmount();
  await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
  expect(load).toHaveBeenCalledTimes(2);
});
