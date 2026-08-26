export type LiveConnectionState = 'live' | 'reconnecting' | 'offline';

const RETRY_DELAYS_MS = [2500, 5000, 10000, 30000] as const;

function retryDelay(attempt: number): number {
  const base = RETRY_DELAYS_MS[Math.min(Math.max(attempt, 0), RETRY_DELAYS_MS.length - 1)];
  return Math.round(base * (0.8 + Math.random() * 0.4));
}

function browserCanRetry(): boolean {
  return document.visibilityState !== 'hidden' && navigator.onLine !== false;
}

export function createRetryScheduler(task: () => void) {
  let timer: number | undefined;
  let pendingAttempt: number | undefined;
  let stopped = false;

  const resume = () => {
    if (stopped || pendingAttempt === undefined || !browserCanRetry()) return;
    const attempt = pendingAttempt;
    pendingAttempt = undefined;
    timer = window.setTimeout(() => {
      timer = undefined;
      if (stopped) return;
      if (!browserCanRetry()) {
        pendingAttempt = attempt;
        return;
      }
      task();
    }, retryDelay(attempt));
  };
  document.addEventListener('visibilitychange', resume);
  window.addEventListener('online', resume);

  return {
    schedule(attempt: number) {
      if (timer) window.clearTimeout(timer);
      pendingAttempt = attempt;
      resume();
    },
    stop() {
      stopped = true;
      pendingAttempt = undefined;
      if (timer) window.clearTimeout(timer);
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('online', resume);
    },
  };
}

export function connectJsonWebSocket<T extends { type: string }>(
  url: string,
  eventType: T['type'],
  onEvent: (event: T) => void,
  onState?: (state: LiveConnectionState) => void,
): () => void {
  let socket: WebSocket | null = null;
  let stopped = false;
  let reconnectAttempt = 0;
  const reconnect = createRetryScheduler(connect);

  function connect() {
    if (stopped) return;
    onState?.('reconnecting');
    socket = new WebSocket(url);
    socket.addEventListener('open', () => {
      reconnectAttempt = 0;
      onState?.('live');
    });
    socket.addEventListener('message', (event) => {
      try {
        const payload = JSON.parse(String(event.data)) as { type?: unknown };
        if (payload.type === eventType) onEvent(payload as T);
      } catch {
        // Los mensajes ajenos al contrato de esta conexión no alteran su estado.
      }
    });
    socket.addEventListener('close', () => {
      if (stopped) return;
      onState?.('offline');
      reconnect.schedule(reconnectAttempt++);
    });
    socket.addEventListener('error', () => {
      onState?.('offline');
      socket?.close();
    });
  }

  connect();
  return () => {
    stopped = true;
    reconnect.stop();
    socket?.close();
  };
}

export function apiWebSocketUrl(path: string, apiBase: string): string {
  const base = apiBase || window.location.origin;
  const url = new URL(path, base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.toString();
}
