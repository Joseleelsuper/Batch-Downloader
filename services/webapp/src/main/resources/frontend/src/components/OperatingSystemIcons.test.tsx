import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  OperatingSystemIcon,
  OperatingSystemList,
  operatingSystemLabel,
} from './OperatingSystemIcons';

afterEach(cleanup);

describe('OperatingSystemIcons', () => {
  it('muestra un estado vacío accesible', () => {
    render(<OperatingSystemList operatingSystems={[]} />);
    expect(screen.getByLabelText('Sin instaladores verificados')).toHaveTextContent('—');
  });

  it('compone la etiqueta de la lista y hace decorativos sus iconos', () => {
    const { container } = render(
      <OperatingSystemList operatingSystems={['windows', 'linux', 'macos']} />,
    );
    expect(screen.getByLabelText('Disponible para Windows, Linux, macOS')).toBeInTheDocument();
    const images = container.querySelectorAll('img');
    expect(images).toHaveLength(3);
    expect(images[0]).toHaveAttribute('alt', '');
    expect(images[0]).toHaveAttribute('aria-hidden', 'true');
  });

  it('expone un icono informativo con tamaño y traductor inyectable', () => {
    render(<OperatingSystemIcon operatingSystem="linux" size={24} />);
    expect(screen.getByAltText('Linux')).toHaveAttribute('width', '24');
    expect(operatingSystemLabel('windows', vi.fn(() => 'Sistema de prueba')))
      .toBe('Sistema de prueba');
  });
});
