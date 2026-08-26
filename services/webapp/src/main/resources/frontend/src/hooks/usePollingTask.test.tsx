import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePollingTask } from './usePollingTask';

describe('usePollingTask', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('espera el intervalo y detiene el ciclo cuando la tarea devuelve false', async () => {
    vi.useFakeTimers();
    const task = vi.fn().mockResolvedValue(false);

    renderHook(() => usePollingTask({ enabled: true, intervalMs: 100, task }));
    expect(task).not.toHaveBeenCalled();
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(task).toHaveBeenCalledOnce();
    await act(() => vi.advanceTimersByTimeAsync(500));
    expect(task).toHaveBeenCalledOnce();
  });

  it('no solapa tareas asíncronas y programa la siguiente al terminar', async () => {
    vi.useFakeTimers();
    let finish: ((value: boolean) => void) | undefined;
    const task = vi.fn(() => new Promise<boolean>((resolve) => { finish = resolve; }));

    renderHook(() => usePollingTask({ enabled: true, intervalMs: 100, task }));
    await act(() => vi.advanceTimersByTimeAsync(100));
    await act(() => vi.advanceTimersByTimeAsync(500));
    expect(task).toHaveBeenCalledOnce();

    await act(async () => { finish?.(true); });
    await act(() => vi.advanceTimersByTimeAsync(99));
    expect(task).toHaveBeenCalledOnce();
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(task).toHaveBeenCalledTimes(2);
  });

  it('aborta la tarea activa al desmontar el consumidor', async () => {
    vi.useFakeTimers();
    let receivedSignal: AbortSignal | undefined;
    const task = vi.fn((signal: AbortSignal) => {
      receivedSignal = signal;
      return new Promise<boolean>(() => undefined);
    });
    const { unmount } = renderHook(() => usePollingTask({
      enabled: true,
      immediate: true,
      intervalMs: 100,
      task,
    }));

    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(receivedSignal?.aborted).toBe(false);
    unmount();
    expect(receivedSignal?.aborted).toBe(true);
  });
});
