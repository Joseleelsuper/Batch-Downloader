import { act, render, screen } from '@testing-library/react';
import { Suspense, type ReactElement } from 'react';
import { describe, expect, it } from 'vitest';
import { lazyNamed } from './lazyNamed';

describe('lazyNamed', () => {
  it('keeps the route fallback visible until the named page resolves', async () => {
    let resolveModule: ((module: { DeferredPage: () => ReactElement }) => void) | undefined;
    const DeferredPage = lazyNamed(
      () => new Promise<{ DeferredPage: () => ReactElement }>((resolve) => {
        resolveModule = resolve;
      }),
      'DeferredPage',
    );

    render(
      <Suspense fallback={<p role="status">Cargando ruta</p>}>
        <DeferredPage />
      </Suspense>,
    );

    expect(screen.getByRole('status')).toHaveTextContent('Cargando ruta');
    await act(async () => {
      resolveModule?.({ DeferredPage: () => <h1>Ruta cargada</h1> });
    });
    expect(await screen.findByRole('heading', { name: 'Ruta cargada' })).toBeVisible();
  });
});
