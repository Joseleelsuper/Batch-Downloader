import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  confirmMagicLink,
  createOwnBundle,
  deleteOwnBundle,
  fetchDashboard,
  fetchDownloads,
  fetchOwnBundle,
  fetchOwnBundles,
  fetchProfile,
  requestMagicLink,
  updateOwnBundle,
  updateProfile,
} from './account';
import { requestJson } from './http';

vi.mock('./http', () => ({ requestJson: vi.fn() }));

const requestJsonMock = vi.mocked(requestJson);

describe('API de cuenta', () => {
  beforeEach(() => {
    requestJsonMock.mockReset();
    requestJsonMock.mockResolvedValue(undefined as never);
  });

  it('construye las solicitudes de enlace de acceso', async () => {
    await requestMagicLink('user@example.com');
    await confirmMagicLink('magic-token');

    expect(requestJsonMock.mock.calls).toEqual([
      ['/api/v1/auth/magic-link/request', {
        method: 'POST', body: JSON.stringify({ email: 'user@example.com' }),
      }],
      ['/api/v1/auth/magic-link/confirm', {
        method: 'POST', body: JSON.stringify({ token: 'magic-token' }),
      }],
    ]);
  });

  it('construye las solicitudes de perfil, panel e historial con paginación', async () => {
    await fetchProfile();
    await updateProfile('new-name');
    await fetchDashboard();
    await fetchDownloads();
    await fetchDownloads(3, 50);

    expect(requestJsonMock.mock.calls).toEqual([
      ['/api/v1/users/me'],
      ['/api/v1/users/me', {
        method: 'PATCH', body: JSON.stringify({ username: 'new-name' }),
      }],
      ['/api/v1/users/me/dashboard'],
      ['/api/v1/users/me/downloads?page=1&pageSize=20'],
      ['/api/v1/users/me/downloads?page=3&pageSize=50'],
    ]);
  });

  it('construye el CRUD de bundles y codifica los identificadores', async () => {
    const input = {
      name: 'Herramientas',
      description: 'Bundle personal',
      slug: 'herramientas',
      tags: ['dev'],
      appIds: ['app-1'],
    };
    await fetchOwnBundles();
    await fetchOwnBundles(2, 5);
    await fetchOwnBundle('bundle/id con espacio');
    await createOwnBundle(input);
    await updateOwnBundle('bundle/id', {
      ...input,
      visibility: 'private',
      expectedVersion: 4,
    });
    await deleteOwnBundle('bundle/id');

    expect(requestJsonMock.mock.calls).toEqual([
      ['/api/v1/users/me/bundles?page=1&pageSize=20'],
      ['/api/v1/users/me/bundles?page=2&pageSize=5'],
      ['/api/v1/users/me/bundles/bundle%2Fid%20con%20espacio'],
      ['/api/v1/users/me/bundles', {
        method: 'POST', body: JSON.stringify(input),
      }],
      ['/api/v1/users/me/bundles/bundle%2Fid', {
        method: 'PATCH',
        body: JSON.stringify({ ...input, visibility: 'private', expectedVersion: 4 }),
      }],
      ['/api/v1/users/me/bundles/bundle%2Fid', { method: 'DELETE' }],
    ]);
  });
});
