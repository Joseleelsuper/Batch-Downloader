import { useEffect, useRef } from 'react';

interface PollingTaskOptions {
  enabled: boolean;
  intervalMs: number;
  pollKey?: string | number | null;
  task: (signal: AbortSignal) => Promise<boolean | void>;
  onError?: (cause: unknown) => void;
  immediate?: boolean;
}

/**
 * Ejecuta polling secuencial y cancelable. Una tarea nunca se solapa con la
 * anterior y puede detener el ciclo devolviendo `false`.
 */
export function usePollingTask({
  enabled,
  intervalMs,
  pollKey = null,
  task,
  onError,
  immediate = false,
}: Readonly<PollingTaskOptions>): void {
  const taskRef = useRef(task);
  const errorRef = useRef(onError);
  taskRef.current = task;
  errorRef.current = onError;

  useEffect(() => {
    if (!enabled) return undefined;

    const controller = new AbortController();
    let active = true;
    let timer: number | undefined;

    const schedule = (delay: number) => {
      timer = window.setTimeout(() => void run(), delay);
    };

    const run = async () => {
      if (!active || controller.signal.aborted) return;
      let keepPolling = true;
      try {
        keepPolling = await taskRef.current(controller.signal) !== false;
      } catch (cause) {
        if (!controller.signal.aborted) errorRef.current?.(cause);
      }
      if (active && !controller.signal.aborted && keepPolling) {
        schedule(intervalMs);
      }
    };

    schedule(immediate ? 0 : intervalMs);
    return () => {
      active = false;
      controller.abort();
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [enabled, immediate, intervalMs, pollKey]);
}
