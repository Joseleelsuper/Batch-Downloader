import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DownloadJob } from '../types/catalog';
import { BundleDownloadButton } from './BundleDownloadButton';

const hooks = vi.hoisted(() => ({
  useDownloadJob: vi.fn(),
}));

vi.mock('../hooks/useDownloadJob', () => hooks);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function activeJob(): DownloadJob {
  return {
    id: 'a51d185b-1966-4c51-83e2-9b2f523f47ce',
    status: 'DOWNLOADING',
    failureCode: null,
    progress: 35,
    requestedCount: 3,
    acceptedCount: 2,
    omittedCount: 1,
    createdAt: '2026-07-11T08:00:00Z',
    expiresAt: '2026-07-12T08:00:00Z',
    items: [],
  };
}

describe('BundleDownloadButton', () => {
  it('mantiene el progreso y la cancelación accesibles en una tarjeta compacta', () => {
    hooks.useDownloadJob.mockReturnValue({
      job: activeJob(),
      starting: false,
      cancelling: false,
      error: false,
      start: vi.fn(),
      cancel: vi.fn(),
      clear: vi.fn(),
    });

    render(<BundleDownloadButton bundleId="bundle-1" appCount={3} operatingSystems={['windows']} compact />);

    expect(screen.getByLabelText('Trabajo de descarga')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    expect(screen.getByText(/1 omitida/i)).toBeInTheDocument();
  });

  it('muestra los sistemas disponibles y envía solo el elegido al crear el trabajo', () => {
    const start = vi.fn().mockResolvedValue(undefined);
    hooks.useDownloadJob.mockReturnValue({
      job: null,
      starting: false,
      cancelling: false,
      error: false,
      start,
      cancel: vi.fn(),
      clear: vi.fn(),
    });

    render(<BundleDownloadButton bundleId="bundle-1" appCount={3} operatingSystems={['windows', 'linux']} />);

    expect(screen.getByRole('button', { name: 'Windows' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Linux' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByRole('button', { name: 'macOS' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Linux' }));
    fireEvent.click(screen.getByRole('button', { name: 'Descargar todas' }));

    expect(start).toHaveBeenCalledWith({ bundleId: 'bundle-1', operatingSystems: ['linux'] });
  });

  it('bloquea la descarga si ninguna aplicación tiene una plataforma disponible', () => {
    hooks.useDownloadJob.mockReturnValue({
      job: null,
      starting: false,
      cancelling: false,
      error: false,
      start: vi.fn(),
      cancel: vi.fn(),
      clear: vi.fn(),
    });

    render(<BundleDownloadButton bundleId="bundle-1" appCount={3} operatingSystems={[]} />);

    expect(screen.getByText('Ninguna aplicación del bundle tiene un instalador disponible.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Descargar todas' })).toBeDisabled();
  });
});
