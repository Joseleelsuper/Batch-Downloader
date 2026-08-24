import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppSearchBar } from './AppSearchBar';

describe('AppSearchBar', () => {
  afterEach(() => cleanup());

  it('opens an explained sort menu and selects most downloaded', () => {
    const onSortChange = vi.fn();
    render(
      <AppSearchBar
        value=""
        sort="updated"
        searchMode="semantic"
        onChange={vi.fn()}
        onSortChange={onSortChange}
        onSearchModeChange={vi.fn()}
      />,
    );

    const trigger = screen.getByRole('button', { name: 'Cambiar orden' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Primero las aplicaciones actualizadas más recientemente.')).toBeVisible();
    expect(screen.getByText('Primero las aplicaciones con más descargas completadas.')).toBeVisible();

    fireEvent.click(screen.getByRole('option', { name: /Más descargadas/ }));

    expect(onSortChange).toHaveBeenCalledWith('downloads');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('supports arrow, home, end and escape navigation in the sort listbox', async () => {
    render(
      <AppSearchBar
        value=""
        sort="updated"
        searchMode="semantic"
        onChange={vi.fn()}
        onSortChange={vi.fn()}
        onSearchModeChange={vi.fn()}
      />,
    );
    const trigger = screen.getByRole('button', { name: 'Cambiar orden' });

    fireEvent.keyDown(trigger, { key: 'ArrowDown' });
    const options = screen.getAllByRole('option');
    await waitFor(() => expect(options[0]).toHaveFocus());

    fireEvent.keyDown(options[0], { key: 'End' });
    await waitFor(() => expect(options[2]).toHaveFocus());
    fireEvent.keyDown(options[2], { key: 'Home' });
    await waitFor(() => expect(options[0]).toHaveFocus());
    fireEvent.keyDown(options[0], { key: 'ArrowUp' });
    await waitFor(() => expect(options[2]).toHaveFocus());

    fireEvent.keyDown(options[2], { key: 'Escape' });
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
