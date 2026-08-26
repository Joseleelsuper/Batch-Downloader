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

  it('normaliza valores no numéricos y no repite la página actual', () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={2}
        pageSize={12}
        total={30}
        onPageChange={onPageChange}
        onPageSizeChange={vi.fn()}
      />,
    );
    const input = screen.getByRole('textbox', { name: 'Página' });
    fireEvent.change(input, { target: { value: 'abc' } });
    expect(input).toHaveValue('');
    fireEvent.blur(input);
    expect(input).toHaveValue('2');
    expect(onPageChange).not.toHaveBeenCalled();
  });

  it('representa un catálogo vacío y permite cambiar el tamaño', () => {
    const onPageSizeChange = vi.fn();
    render(
      <Pagination
        page={1}
        pageSize={12}
        total={0}
        onPageChange={vi.fn()}
        onPageSizeChange={onPageSizeChange}
      />,
    );

    expect(screen.getByText(/Mostrando 0 a 0 de 0/)).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toEqual(
      expect.arrayContaining([expect.objectContaining({ disabled: true })]),
    );
    fireEvent.change(screen.getByRole('combobox', { name: 'Aplicaciones por página' }), {
      target: { value: '24' },
    });
    expect(onPageSizeChange).toHaveBeenCalledWith(24);
  });
});
