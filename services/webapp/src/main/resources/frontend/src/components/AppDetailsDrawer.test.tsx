import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppDetailsDrawer } from './AppDetailsDrawer';
import type { AppDetails } from '../types/catalog';

const app: AppDetails = {
  id: 'geogebra-graphing-calculator',
  packageId: 'GeoGebra.GraphingCalculator',
  name: 'GeoGebra Graphing Calculator',
  publisher: 'International GeoGebra Institute',
  description: 'Dynamic mathematics app.',
  longDescription: 'GeoGebra Graphing Calculator permite crear graficas y explorar funciones.',
  tags: ['math', 'graphing'],
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
      sourceLabel: 'Sitio oficial',
      score: 130,
      finalDomain: 'geogebra.org',
      isPrimary: true,
    },
    {
      id: 'secondary',
      filename: 'GeoGebraSuite.exe',
      extension: '.exe',
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
    expect(screen.getByText(/Principal/)).toBeInTheDocument();
  });

  it('shows an AI pending message when long description is missing', () => {
    render(<AppDetailsDrawer app={{ ...app, longDescription: null }} onClose={vi.fn()} />);

    expect(screen.getByText('Descripcion IA pendiente de generar.')).toBeInTheDocument();
  });
});
