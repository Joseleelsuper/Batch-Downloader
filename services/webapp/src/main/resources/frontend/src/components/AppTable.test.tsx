import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppTable } from './AppTable';
import type { CatalogApp } from '../types/catalog';

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

describe('AppTable', () => {
  afterEach(() => cleanup());

  it('selects rows from catalog results', () => {
    const onSelect = vi.fn();
    render(<AppTable apps={[app]} onSelect={onSelect} />);

    expect(screen.queryByRole('columnheader', { name: 'Fuente' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Epic Games Launcher'));

    expect(onSelect).toHaveBeenCalledWith(app);
    expect(screen.getByLabelText('Disponible para Windows, macOS')).toBeInTheDocument();
  });

  it('toggles selection without selecting the row', () => {
    const onSelect = vi.fn();
    const onToggleSelection = vi.fn();
    render(
      <AppTable
        apps={[app]}
        selectedIds={new Set()}
        selectedCount={0}
        onSelect={onSelect}
        onToggleSelection={onToggleSelection}
      />,
    );

    fireEvent.click(screen.getByRole('checkbox', { name: 'Seleccionar Epic Games Launcher' }));

    expect(onToggleSelection).toHaveBeenCalledWith(app);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('disables new selections when the limit is reached', () => {
    render(
      <AppTable
        apps={[app]}
        selectedIds={new Set()}
        selectedCount={100}
        onSelect={vi.fn()}
        onToggleSelection={vi.fn()}
      />,
    );

    expect(screen.getByRole('checkbox', { name: 'Seleccionar Epic Games Launcher' })).toBeDisabled();
  });
});
