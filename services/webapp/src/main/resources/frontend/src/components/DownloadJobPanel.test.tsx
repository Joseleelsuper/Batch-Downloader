import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { DownloadJob } from '../types/catalog';
import { DownloadJobPanel } from './DownloadJobPanel';

afterEach(cleanup);

function job(status: DownloadJob['status']): DownloadJob {
  return {
    id: 'a51d185b-1966-4c51-83e2-9b2f523f47ce',
    status,
    failureCode: null,
    progress: status === 'READY' ? 100 : 35,
    requestedCount: 1,
    acceptedCount: 1,
    omittedCount: 0,
    createdAt: '2026-07-11T08:00:00Z',
    expiresAt: '2026-07-12T08:00:00Z',
    items: [
      {
        id: '143089b4-8494-4fa7-a365-47dc882c6b72',
        appId: '53184a90-ab4b-4609-bbce-456f913fe691',
        status: status === 'READY' ? 'COMPLETED' : 'DOWNLOADING',
        bytesDownloaded: 1024,
      },
    ],
  };
}

describe('DownloadJobPanel', () => {
  it('permite cancelar un trabajo activo', () => {
    const onCancel = vi.fn();
    render(<DownloadJobPanel job={job('DOWNLOADING')} onCancel={onCancel} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(onCancel).toHaveBeenCalledOnce();
    expect(screen.queryByRole('link', { name: /Obtener ZIP/i })).not.toBeInTheDocument();
  });

  it('expone el ZIP únicamente cuando el trabajo está listo', () => {
    render(<DownloadJobPanel job={job('READY')} onCancel={vi.fn()} onClose={vi.fn()} />);

    const link = screen.getByRole('link', { name: /Obtener ZIP/i });
    expect(link).toHaveAttribute(
      'href',
      '/api/v1/download-jobs/a51d185b-1966-4c51-83e2-9b2f523f47ce/file',
    );
    expect(screen.queryByRole('button', { name: 'Cancelar' })).not.toBeInTheDocument();
  });

  it('conserva el ZIP parcial y muestra el código de los elementos fallidos', () => {
    const partial = job('PARTIAL');
    partial.progress = 100;
    partial.items = [
      { ...partial.items[0], status: 'COMPLETED' },
      {
        id: '243089b4-8494-4fa7-a365-47dc882c6b72',
        appId: '63184a90-ab4b-4609-bbce-456f913fe691',
        status: 'FAILED',
        bytesDownloaded: 0,
        errorCode: 'source_revalidation_failed',
      },
    ];

    render(<DownloadJobPanel job={partial} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('Completado parcialmente')).toBeInTheDocument();
    expect(screen.getByText('Código de error: source_revalidation_failed.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Obtener ZIP/i })).toBeInTheDocument();
  });

  it('muestra el código terminal de un trabajo fallido sin ofrecer un ZIP', () => {
    const failed = { ...job('FAILED'), failureCode: 'all_items_failed' };

    render(<DownloadJobPanel job={failed} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('Fallido')).toBeInTheDocument();
    expect(screen.getByText('Código de error: all_items_failed.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Obtener ZIP/i })).not.toBeInTheDocument();
  });
});
