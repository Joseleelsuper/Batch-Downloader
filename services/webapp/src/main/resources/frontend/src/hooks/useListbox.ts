import { useEffect, useId, useRef, useState, type FocusEvent, type KeyboardEvent, type ToggleEvent } from 'react';

export function useListbox(count: number, selectedIndex: number, closeOnBlur = false) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const options = useRef<Array<HTMLButtonElement | null>>([]);
  const initialIndex = useRef<number | null>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const native = typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.showPopover === 'function'
    && typeof CSS !== 'undefined' && CSS.supports('top: anchor(bottom)');

  function focusOption(index: number) {
    setActive(index);
    window.requestAnimationFrame(() => options.current[index]?.focus());
  }

  function openList() {
    setOpen(true);
    focusOption(initialIndex.current ?? Math.max(0, selectedIndex));
    initialIndex.current = null;
  }

  function close(restoreFocus = true) {
    if (native) list.current?.hidePopover();
    else setOpen(false);
    if (restoreFocus) trigger.current?.focus();
  }

  useEffect(() => {
    if (!open || native) return;
    function dismiss(event: PointerEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    function escape(event: globalThis.KeyboardEvent) {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      setOpen(false);
      trigger.current?.focus();
    }
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', dismiss);
      document.removeEventListener('keydown', escape);
    };
  }, [native, open]);

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const fromTrigger = event.currentTarget === trigger.current;
    const index = event.key === 'ArrowDown' ? (fromTrigger ? 0 : (active + 1) % count)
      : event.key === 'ArrowUp' ? (fromTrigger ? count - 1 : (active + count - 1) % count)
        : !fromTrigger && event.key === 'Home' ? 0
          : !fromTrigger && event.key === 'End' ? count - 1 : null;
    if (index === null || !count) return;
    event.preventDefault();
    if (open) focusOption(index);
    else {
      initialIndex.current = index;
      if (native) trigger.current?.click();
      else openList();
    }
  }

  return {
    close,
    visible: native || open,
    rootProps: {
      ref: root,
      onBlur: closeOnBlur ? (event: FocusEvent<HTMLDivElement>) => {
        if (open && !event.currentTarget.contains(event.relatedTarget)) close(false);
      } : undefined,
    },
    triggerProps: {
      ref: trigger,
      popoverTarget: native ? id : undefined,
      'aria-expanded': open,
      'aria-controls': id,
      onClick: native ? undefined : () => { if (open) close(); else openList(); },
      onKeyDown,
    },
    listProps: {
      id,
      ref: list,
      popover: native ? 'auto' as const : undefined,
      onToggle: native ? (event: ToggleEvent<HTMLDivElement>) => {
        if (event.newState === 'open') openList();
        else setOpen(false);
      } : undefined,
    },
    optionProps: (index: number) => ({
      ref: (element: HTMLButtonElement | null) => { options.current[index] = element; },
      tabIndex: active === index ? 0 : -1,
      onFocus: () => setActive(index),
      onKeyDown,
    }),
  };
}
