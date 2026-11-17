import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { ManualInstallerInspection, ManualInstallerTechnicalData } from '../../types/catalog';
import { InstallerEvidence } from './AdminAppsSupport';

afterEach(cleanup);

const installer: ManualInstallerTechnicalData = {
  finalDomain: 'cdn.example.test', filename: 'app.exe', extension: '.exe',
  architecture: 'x86_64', operatingSystem: 'windows', platformRequired: false,
};

function inspection(technical: ManualInstallerTechnicalData): ManualInstallerInspection {
  return { id: 'inspection-1', appId: 'app-1', status: 'ready', phase: 'ready', expectedAppVersion: 0,
    warnings: [], installers: [technical], createdAt: '2026-11-06T12:00:00Z',
    updatedAt: '2026-11-06T12:00:00Z', expiresAt: '2026-11-07T12:00:00Z' };
}

it('muestra los saltos observados como dominios y estados sin convertirlos en enlaces', () => {
  const { container } = render(<InstallerEvidence inspection={inspection({ ...installer,
    redirects: [{ domain: 'publisher.example', status: 302 }, { domain: 'mirror.example', status: 307 }],
  })} />);
  expect(screen.getByText('Redirecciones HTTP observadas')).toBeInTheDocument();
  expect(screen.getByText('publisher.example (HTTP 302) → mirror.example (HTTP 307) → cdn.example.test')).toBeInTheDocument();
  expect(container.querySelector('a')).toBeNull();
});

it('distingue una inspección directa observada de una traza histórica no disponible', () => {
  const { rerender } = render(<InstallerEvidence inspection={inspection({ ...installer, redirects: [] })} />);
  expect(screen.getByText('Sin redirecciones HTTP')).toBeInTheDocument();
  rerender(<InstallerEvidence inspection={inspection(installer)} />);
  expect(screen.getByText('Traza no disponible')).toBeInTheDocument();
  expect(screen.queryByText('Sin redirecciones HTTP')).not.toBeInTheDocument();
});
