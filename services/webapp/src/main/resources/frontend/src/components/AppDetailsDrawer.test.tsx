import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { AppDetails } from '../types/catalog';
import { AppDetailsDrawer } from './AppDetailsDrawer';

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
  installerFilename: 'GeoGebraGraphing.exe',
  installerType: 'EXE',
  score: 130,
  sizeBytes: 1024,
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

describe('AppDetailsDrawer', () => {
  it('shows multiple detected installers when available', () => {
    render(<AppDetailsDrawer app={app} onClose={vi.fn()} />);

    expect(screen.getByText('Instaladores detectados')).toBeInTheDocument();
    expect(screen.getByText('math')).toBeInTheDocument();
    expect(screen.getByText(/permite crear graficas/)).toBeInTheDocument();
    expect(screen.getAllByText('GeoGebraGraphing.exe')).toHaveLength(2);
    expect(screen.getByText('GeoGebraSuite.exe')).toBeInTheDocument();
    expect(screen.getByText(/130 - Última/)).toBeInTheDocument();
  });

  it('falls back to the short description when long description is missing', () => {
    render(<AppDetailsDrawer app={{ ...app, longDescription: null }} onClose={vi.fn()} />);

    expect(screen.getByText('Dynamic mathematics app.')).toBeInTheDocument();
  });

  it('shows an AI pending message when both descriptions are missing', () => {
    render(
      <AppDetailsDrawer
        app={{ ...app, description: null, longDescription: null }}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByText('Descripción IA pendiente de generar.')).toBeInTheDocument();
  });
});
