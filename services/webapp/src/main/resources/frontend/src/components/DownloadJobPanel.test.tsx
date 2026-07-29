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
        appName: 'Aplicación de ejemplo',
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
        appName: 'Aplicación fallida',
        officialPageUrl: 'https://example.com/download',
        status: 'FAILED',
        bytesDownloaded: 0,
        errorCode: 'source_revalidation_failed',
      },
    ];

    render(<DownloadJobPanel job={partial} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('Completado parcialmente')).toBeInTheDocument();
    expect(screen.getByText('Aplicación fallida')).toBeInTheDocument();
    expect(screen.getByText('No se pudo obtener un instalador válido desde la fuente configurada.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Abrir página oficial' })).toHaveAttribute(
      'href',
      'https://example.com/download',
    );
    fireEvent.click(screen.getByText('Detalles técnicos'));
    expect(screen.getByText('source_revalidation_failed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Obtener ZIP/i })).toBeInTheDocument();
  });

  it('muestra el código terminal de un trabajo fallido sin ofrecer un ZIP', () => {
    const failed = { ...job('FAILED'), failureCode: 'all_items_failed' };

    render(<DownloadJobPanel job={failed} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('Fallido')).toBeInTheDocument();
    expect(screen.getByText('Problema del servicio de descarga')).toBeInTheDocument();
    expect(screen.getByText('La aplicación no pudo descargarse por un error inesperado.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Obtener ZIP/i })).not.toBeInTheDocument();
  });

  it('mantiene disponible el ZIP cuando solo contiene accesos manuales', () => {
    const manual = job('MANUAL_ONLY');
    manual.progress = 100;
    manual.items = [{
      ...manual.items[0],
      status: 'FAILED',
      bytesDownloaded: 0,
      errorCode: 'remote_unavailable',
      officialPageUrl: 'https://example.com',
    }];

    render(<DownloadJobPanel job={manual} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('Descarga manual necesaria')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Obtener ZIP/i })).toBeInTheDocument();
  });

  it('no convierte una página oficial insegura en un enlace', () => {
    const partial = job('PARTIAL');
    partial.items = [{
      ...partial.items[0],
      status: 'FAILED',
      bytesDownloaded: 0,
      errorCode: 'source_not_verified',
      officialPageUrl: 'javascript:alert(1)',
    }];

    render(<DownloadJobPanel job={partial} onCancel={vi.fn()} onClose={vi.fn()} />);

    expect(screen.queryByRole('link', { name: 'Abrir página oficial' })).not.toBeInTheDocument();
    expect(screen.getByText('Esta aplicación no tiene una página oficial segura disponible.')).toBeInTheDocument();
  });
});
