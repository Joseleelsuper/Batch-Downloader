import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { AdminTable } from './AdminTable';

afterEach(cleanup);

describe('AdminTable', () => {
  it('presenta un estado vacío accesible', () => {
    render(<AdminTable title="Auditoría" rows={[]} />);
    expect(screen.getByRole('heading', { name: 'Auditoría' })).toBeInTheDocument();
    expect(screen.getByText('Sin registros.')).toBeInTheDocument();
  });

  it('adapta la cuadrícula al número de columnas de cada fila', () => {
    const { container } = render(
      <AdminTable title="Colas" rows={[["uno", "dos"], ["tres"]]} />,
    );
    const rows = container.querySelectorAll('.admin-table-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveStyle({ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' });
    expect(rows[1]).toHaveStyle({ gridTemplateColumns: 'repeat(1, minmax(0, 1fr))' });
  });
});
