import { afterEach, describe, expect, it, vi } from 'vitest';
import { createDownloadJob, fetchAdminApps, me } from './catalog';

describe('current identity', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('maps the anonymous 204 response to null', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);

    await expect(me()).resolves.toBeNull();
    expect(fetcher).toHaveBeenCalledWith('/api/v1/auth/me', expect.objectContaining({
      credentials: 'include',
    }));
  });

  it('returns the authenticated identity from a 200 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      username: 'admin',
      role: 'ADMIN',
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })));

    await expect(me()).resolves.toEqual({ username: 'admin', role: 'ADMIN' });
  });

  it('incluye el sistema operativo elegido al crear un trabajo para un bundle', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-token' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: 'job-1',
        status: 'QUEUED',
        failureCode: null,
        progress: 0,
        requestedCount: 16,
        acceptedCount: 16,
        omittedCount: 0,
        items: [],
        createdAt: '2026-07-16T09:00:00Z',
        expiresAt: '2026-07-17T09:00:00Z',
      }), { status: 202 }));
    vi.stubGlobal('fetch', fetcher);

    await createDownloadJob({ bundleId: 'bundle-1', operatingSystems: ['linux'] });

    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', expect.objectContaining({
      credentials: 'include',
    }));
    expect(fetcher).toHaveBeenNthCalledWith(2, '/api/v1/download-jobs', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ bundleId: 'bundle-1', operatingSystems: ['linux'] }),
    }));
  });

  it('envía explícitamente el filtro all al listado administrativo', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      data: [],
      page: 1,
      pageSize: 12,
      total: 0,
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetcher);

    await fetchAdminApps({
      query: '',
      filter: 'all',
      sort: 'updated',
      page: 1,
      pageSize: 12,
    });

    expect(fetcher).toHaveBeenCalledWith(
      '/api/admin/apps?status=all&sort=updated&page=1&pageSize=12',
      expect.objectContaining({ credentials: 'include' }),
    );
  });
});
