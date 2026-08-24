import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DownloadJob } from '../types/catalog';
import { DownloadButton } from './DownloadButton';

const hooks = vi.hoisted(() => ({ useDownloadJob: vi.fn() }));
vi.mock('../hooks/useDownloadJob', () => hooks);

function job(status: DownloadJob['status'], progress = 0): DownloadJob {
  return {
    id: 'job-1', status, failureCode: null, progress,
    requestedCount: 1, acceptedCount: 1, omittedCount: 0, items: [],
    createdAt: '2026-08-24T10:00:00Z', expiresAt: '2026-08-25T10:00:00Z',
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('DownloadButton', () => {
  it('inicia una descarga con fuente y usa el id como etiqueta de respaldo', () => {
    const start = vi.fn().mockResolvedValue(undefined);
    hooks.useDownloadJob.mockReturnValue({
      job: null, starting: false, error: false, start,
    });
    render(<DownloadButton appId="app-1" sourceRef="source-1" />);

    fireEvent.click(screen.getByRole('button', { name: 'Descargar' }));
    expect(start).toHaveBeenCalledWith(
      { appIds: ['app-1'], sourceRef: 'source-1' },
      'Descarga de app-1',
    );
  });

  it('muestra el estado de creación y un error accesible', () => {
    hooks.useDownloadJob.mockReturnValue({
      job: null, starting: true, error: true, start: vi.fn(),
    });
    render(<DownloadButton appId="app-1" appName="Example" disabled />);

    const button = screen.getByRole('button', { name: 'Creando...' });
    expect(button).toBeDisabled();
    expect(button).toHaveClass('download-button-disabled');
    expect(button).toHaveAttribute('title', 'No se pudo actualizar el trabajo de descarga.');
  });

  it('bloquea un trabajo activo y presenta su progreso', () => {
    hooks.useDownloadJob.mockReturnValue({
      job: job('DOWNLOADING', 47), starting: false, error: false, start: vi.fn(),
    });
    render(<DownloadButton appId="app-1" />);
    expect(screen.getByRole('button', { name: '47%' })).toBeDisabled();
  });

  it('permite reintentar tras un estado fallido y reconoce ZIP terminales', () => {
    const start = vi.fn().mockResolvedValue(undefined);
    hooks.useDownloadJob.mockReturnValue({
      job: job('FAILED'), starting: false, error: false, start,
    });
    const failed = render(<DownloadButton appId="app-1" appName="Example" />);
    fireEvent.click(screen.getByRole('button', { name: 'Descargar' }));
    expect(start).toHaveBeenCalledWith(
      { appIds: ['app-1'], sourceRef: undefined },
      'Descarga de Example',
    );
    failed.unmount();

    hooks.useDownloadJob.mockReturnValue({
      job: job('PARTIAL', 100), starting: false, error: false, start: vi.fn(),
    });
    render(<DownloadButton appId="app-1" />);
    expect(screen.getByRole('button', { name: 'Obtener ZIP' })).toBeEnabled();
  });
});
