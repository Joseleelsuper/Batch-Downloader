import { act, cleanup, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { DeferredSection } from './DeferredSection';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it('carga inmediatamente si IntersectionObserver no está disponible', () => {
  vi.stubGlobal('IntersectionObserver', undefined);
  render(<DeferredSection><h2>Contenido</h2></DeferredSection>);
  expect(screen.getByRole('heading', { name: 'Contenido' })).toBeInTheDocument();
});

it('anticipa a 300 px, monta una sola vez y conserva el contenido al salir de pantalla', () => {
  let callback!: IntersectionObserverCallback;
  let options: IntersectionObserverInit | undefined;
  const observe = vi.fn();
  const disconnect = vi.fn();
  const mounted = vi.fn();
  const unmounted = vi.fn();
  vi.stubGlobal('IntersectionObserver', class {
    constructor(next: IntersectionObserverCallback, config: IntersectionObserverInit) { callback = next; options = config; }
    observe = observe;
    disconnect = disconnect;
  });
  function Content() {
    useEffect(() => { mounted(); return unmounted; }, []);
    return <h2>Contenido diferido</h2>;
  }
  const { unmount } = render(<DeferredSection><Content /></DeferredSection>);
  expect(options).toEqual({ rootMargin: '300px', threshold: 0 });
  expect(observe).toHaveBeenCalledTimes(1);
  expect(mounted).not.toHaveBeenCalled();
  const notify = (isIntersecting: boolean) => callback([{ isIntersecting } as IntersectionObserverEntry], {} as IntersectionObserver);
  act(() => notify(false));
  expect(mounted).not.toHaveBeenCalled();
  act(() => notify(true));
  expect(screen.getByRole('heading', { name: 'Contenido diferido' })).toBeInTheDocument();
  act(() => notify(false));
  expect(mounted).toHaveBeenCalledTimes(1);
  expect(unmounted).not.toHaveBeenCalled();
  unmount();
  expect(unmounted).toHaveBeenCalledTimes(1);
  expect(disconnect).toHaveBeenCalled();
});

it('desconecta el observador si la sección desaparece antes de activarse', () => {
  const disconnect = vi.fn();
  vi.stubGlobal('IntersectionObserver', class { observe = vi.fn(); disconnect = disconnect; });
  const { unmount } = render(<DeferredSection><h2>Contenido</h2></DeferredSection>);
  unmount();
  expect(disconnect).toHaveBeenCalledTimes(1);
});
