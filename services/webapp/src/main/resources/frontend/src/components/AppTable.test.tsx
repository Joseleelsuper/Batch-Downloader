import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppTable } from './AppTable';
import type { AppDetails, CatalogApp } from '../types/catalog';

vi.mock('../hooks/useDownloadJob', () => ({
  useDownloadJob: () => ({
    job: null,
    starting: false,
    cancelling: false,
    error: false,
    start: vi.fn(),
    cancel: vi.fn(),
    clear: vi.fn(),
  }),
}));

const app: CatalogApp = {
  id: '11111111-1111-4111-8111-111111111111',
  slug: 'epic-games-launcher',
  packageId: 'EpicGames.EpicGamesLauncher',
  name: 'Epic Games Launcher',
  publisher: 'Epic Games, Inc.',
  description: 'Game store',
  tags: ['games'],
  operatingSystems: ['windows', 'macos'],
  latestVersion: '1.0.0',
  sourceLabel: 'Sitio oficial',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-06-26T03:00:00Z',
};

const details: AppDetails = {
  ...app,
  longDescription: 'Un detalle desplegado debajo de la aplicación.',
  notes: '',
  downloadOptions: [],
};

describe('AppTable', () => {
  afterEach(() => cleanup());

  it('opens application details from the row or its chevron without a status column', () => {
    const onToggleDetails = vi.fn();
    render(<AppTable apps={[app]} onToggleDetails={onToggleDetails} />);

    expect(screen.queryByRole('columnheader', { name: 'Fuente' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Estado' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mostrar detalles de Epic Games Launcher' }))
      .toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(screen.getByText('Epic Games Launcher'));

    expect(onToggleDetails).toHaveBeenCalledWith(app);
    expect(screen.getByLabelText('Disponible para Windows, macOS')).toBeInTheDocument();
  });

  it('toggles selection without selecting the row', () => {
    const onToggleDetails = vi.fn();
    const onToggleSelection = vi.fn();
    render(
      <AppTable
        apps={[app]}
        selectedIds={new Set()}
        selectedCount={0}
        onToggleDetails={onToggleDetails}
        onToggleSelection={onToggleSelection}
      />,
    );

    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Epic Games Launcher' }));

    expect(onToggleSelection).toHaveBeenCalledWith(app);
    expect(onToggleDetails).not.toHaveBeenCalled();
  });

  it('disables new selections when the limit is reached', () => {
    render(
      <AppTable
        apps={[app]}
        selectedIds={new Set()}
        selectedCount={100}
        onToggleDetails={vi.fn()}
        onToggleSelection={vi.fn()}
      />,
    );

    expect(screen.getByRole('checkbox', { name: 'Seleccionar Epic Games Launcher' })).toBeDisabled();
  });

  it('disables inconsistent rows even when the payload marks them downloadable', () => {
    render(
      <AppTable
        apps={[{ ...app, resolutionStatus: 'requires_manual_review', downloadable: true }]}
        onToggleDetails={vi.fn()}
        onToggleSelection={vi.fn()}
      />,
    );

    expect(screen.getByRole('checkbox', { name: 'Seleccionar Epic Games Launcher' })).toBeDisabled();
    expect(screen.queryByText('Revisión')).not.toBeInTheDocument();
  });

  it('keeps the detail row mounted while its closing animation runs', () => {
    const { container, rerender } = render(
      <AppTable
        apps={[app]}
        details={details}
        onToggleDetails={vi.fn()}
      />,
    );

    expect(container.querySelector('.app-detail-row')).not.toHaveClass('app-detail-row-open');

    rerender(
      <AppTable
        apps={[app]}
        selectedId={app.id}
        details={details}
        onToggleDetails={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Ocultar detalles de Epic Games Launcher' }))
      .toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Un detalle desplegado debajo de la aplicación.')).toBeInTheDocument();
    expect(container.querySelector('.app-detail-row')).toHaveClass('app-detail-row-open');

    rerender(
      <AppTable
        apps={[app]}
        details={details}
        onToggleDetails={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: 'Mostrar detalles de Epic Games Launcher' }))
      .toHaveAttribute('aria-expanded', 'false');
    expect(container.querySelector('.app-detail-row')).not.toHaveClass('app-detail-row-open');
    expect(container.querySelector('.app-detail-expander-inner')).toHaveAttribute('aria-hidden', 'true');
  });

  it('hides the empty result message while a replacement page is loading', () => {
    render(<AppTable apps={[]} loading onToggleDetails={vi.fn()} />);

    expect(screen.getByText('Cargando...')).toBeInTheDocument();
    expect(screen.queryByText('No hay aplicaciones que coincidan con la búsqueda.')).not.toBeInTheDocument();
  });
});
