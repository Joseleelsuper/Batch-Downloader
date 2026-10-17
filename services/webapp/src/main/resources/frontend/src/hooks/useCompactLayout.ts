import { useSyncExternalStore } from 'react';

// Debe coincidir con el cambio de navegación de responsive.css.
const COMPACT_LAYOUT = '(max-width: 1023px)';

function subscribe(onChange: () => void) {
  const query = window.matchMedia?.(COMPACT_LAYOUT);
  query?.addEventListener('change', onChange);
  return () => query?.removeEventListener('change', onChange);
}

export function useCompactLayout() {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia?.(COMPACT_LAYOUT).matches ?? false,
    () => false,
  );
}
