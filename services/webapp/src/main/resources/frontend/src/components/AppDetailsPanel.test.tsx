import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AppDetails } from '../types/catalog';
import { AppDetailsPanel } from './AppDetailsPanel';

const downloadJob = vi.hoisted(() => ({ start: vi.fn() }));

vi.mock('../hooks/useDownloadJob', () => ({
  useDownloadJob: () => ({
    job: null,
    starting: false,
    cancelling: false,
    error: false,
    start: downloadJob.start,
    cancel: vi.fn(),
    clear: vi.fn(),
  }),
}));

const app: AppDetails = {
  id: '22222222-2222-4222-8222-222222222222',
  slug: 'geogebra-graphing-calculator',
  packageId: 'GeoGebra.GraphingCalculator',
  name: 'GeoGebra Graphing Calculator',
  publisher: 'International GeoGebra Institute',
  description: 'Dynamic mathematics app.',
  longDescription: 'GeoGebra Graphing Calculator permite crear graficas y explorar funciones.',
  tags: ['math', 'graphing'],
  operatingSystems: ['windows'],
  latestVersion: '6.0.920',
  sourceLabel: 'Sitio oficial',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-06-26T03:00:00Z',
  officialUrl: 'https://www.geogebra.org',
  originUrl: 'https://winstall.app/apps/GeoGebra.GraphingCalculator',
  installerFilename: 'GeoGebraGraphing.exe',
  installerType: 'EXE',
  score: 130,
  sizeBytes: 13_107_200,
  checkedAt: '2026-06-26T03:00:00Z',
  notes: 'Instalador obtenido directamente desde el sitio oficial.',
  downloadOptions: [
    {
      id: 'primary',
      filename: 'GeoGebraGraphing.exe',
      extension: '.exe',
      operatingSystem: 'windows',
      architecture: 'x86_64',
      version: '6.0.920',
      isLatest: true,
      versionStatus: 'latest',
      sourceLabel: 'Sitio oficial',
      score: 130,
      finalDomain: 'geogebra.org',
      isPrimary: true,
    },
    {
      id: 'secondary',
      filename: 'GeoGebraSuite.exe',
      extension: '.exe',
      operatingSystem: 'windows',
      architecture: 'x86_64',
      version: '6.0.910',
      isLatest: false,
      versionStatus: 'previous',
      sourceLabel: 'Sitio oficial',
      score: 80,
      finalDomain: 'geogebra.org',
      isPrimary: false,
    },
  ],
};

describe('AppDetailsPanel', () => {
  afterEach(() => {
    cleanup();
    downloadJob.start.mockReset();
  });

  it('shows only the useful catalog details and actions', () => {
    render(<AppDetailsPanel app={app} />);

    expect(screen.getByText(/permite crear graficas/)).toBeInTheDocument();
    expect(screen.getByText('math')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /geogebra\.org/i })).toHaveAttribute(
      'href',
      'https://www.geogebra.org',
    );
    expect(screen.getByText('12,5 MB')).toBeInTheDocument();
    expect(screen.getByText('GeoGebraGraphing.exe')).toBeInTheDocument();
    expect(screen.getByText('GeoGebraSuite.exe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Descargar' })).toBeEnabled();
    expect(screen.getByRole('link', { name: 'Ver origen' })).toHaveAttribute(
      'href',
      'https://winstall.app/apps/GeoGebra.GraphingCalculator',
    );

    expect(screen.queryByText('Estado')).not.toBeInTheDocument();
    expect(screen.queryByText('Confianza')).not.toBeInTheDocument();
    expect(screen.queryByText('Tipo')).not.toBeInTheDocument();
    expect(screen.queryByText('Fuente')).not.toBeInTheDocument();
    expect(screen.queryByText('Notas')).not.toBeInTheDocument();
    expect(screen.queryByText('Instalador detectado')).not.toBeInTheDocument();
    expect(screen.queryByText('Sitio oficial')).not.toBeInTheDocument();
    expect(screen.queryByText('130')).not.toBeInTheDocument();
  });

  it('falls back to the short description when the long one is missing', () => {
    render(<AppDetailsPanel app={{ ...app, longDescription: null }} />);

    expect(screen.getByText('Dynamic mathematics app.')).toBeInTheDocument();
  });

  it('sends the selected installer source when creating the download', () => {
    downloadJob.start.mockResolvedValue({});
    render(<AppDetailsPanel app={app} />);

    fireEvent.click(screen.getByRole('button', { name: /GeoGebraSuite\.exe/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Descargar' }));

    expect(downloadJob.start).toHaveBeenCalledWith(
      { appIds: [app.id], sourceRef: 'secondary' },
      expect.stringContaining('6.0.910'),
    );
  });

  it('shows a pending message when both descriptions are missing', () => {
    render(<AppDetailsPanel app={{ ...app, description: null, longDescription: null }} />);

    expect(screen.getByText('Descripción IA pendiente de generar.')).toBeInTheDocument();
  });

  it('keeps review applications non-downloadable without exposing their status', () => {
    render(
      <AppDetailsPanel
        app={{ ...app, resolutionStatus: 'requires_manual_review', downloadable: true }}
      />,
    );

    expect(screen.getByRole('button', { name: 'Descargar' })).toBeDisabled();
    expect(screen.queryByText('Revisión')).not.toBeInTheDocument();
  });
});
