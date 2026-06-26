import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppTable } from './AppTable';
import type { CatalogApp } from '../types/catalog';

const app: CatalogApp = {
  id: 'epic-games-launcher',
  packageId: 'EpicGames.EpicGamesLauncher',
  name: 'Epic Games Launcher',
  publisher: 'Epic Games, Inc.',
  description: 'Game store',
  latestVersion: '1.0.0',
  sourceLabel: 'Sitio oficial',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-06-26T03:00:00Z',
};

describe('AppTable', () => {
  it('selects rows from catalog results', () => {
    const onSelect = vi.fn();
    render(<AppTable apps={[app]} onSelect={onSelect} />);

    expect(screen.queryByRole('columnheader', { name: 'Fuente' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Epic Games Launcher'));

    expect(onSelect).toHaveBeenCalledWith(app);
  });
});
