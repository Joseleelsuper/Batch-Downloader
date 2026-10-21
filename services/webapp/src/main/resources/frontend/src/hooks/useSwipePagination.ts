import { useRef, type MouseEvent, type TouchEvent } from 'react';

interface Options {
  enabled: boolean;
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
}

const SWIPE_DISTANCE = 60;
const DIRECTION_THRESHOLD = 10;

/** Pagina con un dedo sin capturar el scroll vertical ni los gestos de zoom. */
export function useSwipePagination({ enabled, page, pageCount, onPageChange }: Options) {
  const start = useRef<{ x: number; y: number; identifier: number } | null>(null);
  const suppressClickUntil = useRef(0);

  function onTouchStart(event: TouchEvent<HTMLElement>) {
    start.current = null;
    suppressClickUntil.current = 0;
    if (!enabled || event.touches.length !== 1
      || (event.target as Element).closest('input, select, textarea, [contenteditable="true"]')) return;
    const touch = event.touches[0];
    start.current = { x: touch.clientX, y: touch.clientY, identifier: touch.identifier };
  }

  function onTouchMove(event: TouchEvent<HTMLElement>) {
    const origin = start.current;
    if (!origin) return;
    const touch = event.touches[0];
    if (event.touches.length !== 1 || touch.identifier !== origin.identifier
      || (Math.abs(touch.clientY - origin.y) > DIRECTION_THRESHOLD
        && Math.abs(touch.clientY - origin.y) >= Math.abs(touch.clientX - origin.x))) {
      start.current = null;
    }
  }

  function onTouchEnd(event: TouchEvent<HTMLElement>) {
    const origin = start.current;
    start.current = null;
    if (!enabled || !origin || event.touches.length !== 0) return;
    const touch = Array.from(event.changedTouches).find((candidate) => candidate.identifier === origin.identifier);
    if (!touch) return;
    const deltaX = touch.clientX - origin.x;
    const deltaY = touch.clientY - origin.y;
    if (Math.abs(deltaX) < SWIPE_DISTANCE || Math.abs(deltaX) < Math.abs(deltaY) * 1.5) return;
    suppressClickUntil.current = Date.now() + 500;
    const nextPage = page + (deltaX < 0 ? 1 : -1);
    if (nextPage >= 1 && nextPage <= pageCount) onPageChange(nextPage);
  }

  function onClickCapture(event: MouseEvent<HTMLElement>) {
    if (event.detail === 0 || Date.now() >= suppressClickUntil.current) return;
    event.preventDefault();
    event.stopPropagation();
  }

  return {
    onTouchStart,
    onTouchMove,
    onTouchEnd,
    onTouchCancel: () => { start.current = null; },
    onClickCapture,
  };
}
