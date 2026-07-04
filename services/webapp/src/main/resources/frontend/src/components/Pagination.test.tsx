import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Pagination } from './Pagination';

describe('Pagination', () => {
  afterEach(() => cleanup());

  it('clamps typed page values to the available range', () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={2}
        pageSize={10}
        total={35}
        onPageChange={onPageChange}
        onPageSizeChange={vi.fn()}
      />,
    );

    const input = screen.getByRole('textbox', { name: 'Página' });
    fireEvent.change(input, { target: { value: '99' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onPageChange).toHaveBeenCalledWith(4);
  });

  it('uses previous and next buttons', () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={2}
        pageSize={10}
        total={35}
        onPageChange={onPageChange}
        onPageSizeChange={vi.fn()}
      />,
    );

    const buttons = screen.getAllByRole('button');
    fireEvent.click(buttons[0]);
    fireEvent.click(buttons[1]);

    expect(onPageChange).toHaveBeenNthCalledWith(1, 1);
    expect(onPageChange).toHaveBeenNthCalledWith(2, 3);
  });
});
