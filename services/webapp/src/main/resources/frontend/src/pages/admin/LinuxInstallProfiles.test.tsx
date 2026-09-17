import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestJson } from '../../api/http';
import type { AppDetails } from '../../types/catalog';
import { LinuxInstallProfiles } from './LinuxInstallProfiles';

vi.mock('../../api/http', () => ({ requestJson: vi.fn() }));

const app: AppDetails = {
  id: '11111111-1111-4111-8111-111111111111',
  slug: 'linux-app',
  packageId: 'Example.Linux',
  name: 'Linux App',
  publisher: 'Example',
  description: null,
  longDescription: null,
  tags: [],
  operatingSystems: ['linux'],
  iconUrl: null,
  latestVersion: '1.0',
  sourceLabel: 'Official',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-09-08T00:00:00Z',
  notes: '',
  downloadOptions: [{
    id: '22222222-2222-4222-8222-222222222222',
    filename: 'example.AppImage',
    extension: '.appimage',
    operatingSystem: 'linux',
    architecture: 'x86_64',
    version: '1.0',
    isLatest: true,
    sourceLabel: 'Official',
    score: 100,
    isPrimary: true,
  }],
};

function openEditor() {
  render(<LinuxInstallProfiles app={app} />);
  fireEvent.click(screen.getByText('Instalación Linux y dependencias'));
}

describe('LinuxInstallProfiles', () => {
  beforeEach(() => {
    vi.mocked(requestJson).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('uses the returned profile version for each subsequent CAS update', async () => {
    vi.mocked(requestJson)
      .mockResolvedValueOnce({
        version: 7, status: 'draft', profile: { schemaVersion: 1, strategy: 'appimage' },
      })
      .mockResolvedValueOnce({ version: 3, dependencies: [] })
      .mockResolvedValueOnce({
        version: 8, status: 'approved', profile: { schemaVersion: 1, strategy: 'appimage' },
      })
      .mockResolvedValueOnce({
        version: 9, status: 'approved', profile: { schemaVersion: 1, strategy: 'appimage' },
      });
    openEditor();

    await screen.findByDisplayValue(/"strategy": "appimage"/);
    fireEvent.change(screen.getByLabelText('Estado de la receta'), {
      target: { value: 'approved' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar receta' }));
    await screen.findByText('Guardado. Se utilizará en próximas descargas.');

    let profileWrites = vi.mocked(requestJson).mock.calls.filter(([, options]) => options?.method === 'PUT');
    expect(JSON.parse(String(profileWrites[0]?.[1]?.body))).toMatchObject({
      expectedVersion: 7,
      status: 'approved',
    });

    fireEvent.click(screen.getByRole('button', { name: 'Guardar receta' }));
    await waitFor(() => {
      profileWrites = vi.mocked(requestJson).mock.calls.filter(([, options]) => options?.method === 'PUT');
      expect(profileWrites).toHaveLength(2);
    });
    expect(JSON.parse(String(profileWrites[1]?.[1]?.body))).toMatchObject({ expectedVersion: 8 });
  });

  it('deduplicates dependency UUIDs and sends their independent CAS version', async () => {
    vi.mocked(requestJson)
      .mockResolvedValueOnce({
        version: 7, status: 'draft', profile: { schemaVersion: 1, strategy: 'appimage' },
      })
      .mockResolvedValueOnce({ version: 3, dependencies: ['dependency-one'] })
      .mockResolvedValueOnce({ version: 4, dependencies: ['dependency-one', 'dependency-two'] });
    openEditor();

    const dependencies = await screen.findByLabelText(/Dependencias: un UUID/);
    fireEvent.change(dependencies, {
      target: { value: 'dependency-one\ndependency-two\ndependency-one' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar dependencias' }));

    await waitFor(() => expect(requestJson).toHaveBeenCalledTimes(3));
    const write = vi.mocked(requestJson).mock.calls[2];
    expect(write?.[0]).toMatch(/\/dependencies$/);
    expect(JSON.parse(String(write?.[1]?.body))).toEqual({
      expectedVersion: 3,
      dependencies: ['dependency-one', 'dependency-two'],
    });
  });
});
