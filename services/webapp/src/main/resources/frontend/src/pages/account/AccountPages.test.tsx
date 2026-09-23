import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../../api/account';
import * as catalogAppsApi from '../../api/catalogApps';
import { ApiRequestError } from '../../api/http';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import { t } from '../../services/i18n';
import type { AuthUser, CatalogApp } from '../../types/catalog';
import {
  AccountBundlesPage,
  AccountLayout,
  AdminLoginPage,
  BundleEditorPage,
  DashboardPage,
  ProfilePage,
  UserLoginPage,
} from './AccountPages';

const user: AuthUser = {
  id: '00000000-0000-0000-0000-000000000123',
  username: 'person',
  email: 'person@example.com',
  emailVerified: true,
  role: 'USER',
  createdAt: '2026-08-08T00:00:00Z',
};

const app: CatalogApp = {
  id: '00000000-0000-0000-0000-000000000321',
  slug: 'sample-app',
  packageId: 'Example.Sample',
  name: 'Sample App',
  publisher: 'Example',
  tags: [],
  operatingSystems: ['windows'],
  sourceLabel: 'Official',
  resolutionStatus: 'direct',
  validationStatus: 'valid',
  downloadable: true,
  updatedAt: '2026-08-08T00:00:00Z',
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}{location.hash}</output>;
}

function ProfileAfterAuthentication() {
  const auth = useAuth();
  return auth.status === 'authenticated' ? <ProfilePage /> : <p>checking</p>;
}

describe('account flows', () => {
  beforeEach(() => {
    vi.spyOn(accountApi, 'me').mockResolvedValue(null);
    vi.spyOn(accountApi, 'logout').mockResolvedValue(undefined);
    vi.spyOn(accountApi, 'adminLogout').mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('solicita un enlace de acceso sin contraseña', async () => {
    const request = vi.spyOn(accountApi, 'requestMagicLink').mockResolvedValue(undefined);
    const { container } = render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes><Route path="/login" element={<UserLoginPage />} /></Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(container.querySelector('input[type="password"]')).toBeNull();
    expect(screen.getByRole('heading', { name: 'Inicia sesión' })).toBeInTheDocument();
    expect(screen.queryByText('Te enviaremos un enlace de acceso de un solo uso. No necesitas contraseña.')).toBeNull();
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(request).toHaveBeenCalledWith('person@example.com', 'es'));
    expect(await screen.findByText(t('account.magic.sent'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('account.adminLogin.link') })).toHaveClass('auth-switch-link');
  });

  it('valida el correo y traduce los errores al solicitar el enlace', async () => {
    const request = vi.spyOn(accountApi, 'requestMagicLink').mockRejectedValue(
      new ApiRequestError(429, 'rate_limited'),
    );
    const { container } = render(
      <MemoryRouter><AuthProvider><UserLoginPage /></AuthProvider></MemoryRouter>,
    );
    const email = container.querySelector('input[type="email"]')!;

    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.email.invalid'))).toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();

    fireEvent.change(email, { target: { value: `${'a'.repeat(245)}@example.com` } });
    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.email.tooLong'))).toBeInTheDocument();
    expect(request).not.toHaveBeenCalled();

    fireEvent.change(email, { target: { value: 'person@example.com' } });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText(t('account.error.rate_limited'))).toBeInTheDocument();
  });

  it('consume el token del fragmento, lo elimina de la URL y conserva el destino', async () => {
    const confirm = vi.spyOn(accountApi, 'confirmMagicLink').mockResolvedValue(user);
    render(
      <MemoryRouter initialEntries={[{
        pathname: '/login',
        hash: '#token=secret-token',
        state: { from: { pathname: '/dashboard' } },
      }]}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<><UserLoginPage /><LocationProbe /></>} />
            <Route path="/dashboard" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(confirm).toHaveBeenCalledWith('secret-token'));
    expect(await screen.findByTestId('location')).toHaveTextContent('/dashboard');
    expect(screen.getByTestId('location')).not.toHaveTextContent('token=');
  });

  it('mantiene el acceso administrativo con contraseña', async () => {
    const admin = { ...user, username: 'admin', role: 'ADMIN' as const };
    const login = vi.spyOn(accountApi, 'adminLogin').mockResolvedValue(admin);
    const { container } = render(
      <MemoryRouter initialEntries={['/admin/login']}>
        <AuthProvider>
          <Routes>
            <Route path="/admin/login" element={<AdminLoginPage />} />
            <Route path="/admin" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: t('account.adminLogin.title') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: t('account.userLogin.link') })).toHaveClass('auth-switch-link');
    const inputs = container.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'admin' } });
    fireEvent.change(inputs[1], { target: { value: 'secret' } });
    fireEvent.submit(container.querySelector('form')!);
    await waitFor(() => expect(login).toHaveBeenCalledWith('admin', 'secret'));
    expect(await screen.findByTestId('location')).toHaveTextContent('/admin');
  });

  it('valida la contraseña administrativa antes de enviarla', () => {
    const login = vi.spyOn(accountApi, 'adminLogin').mockResolvedValue({
      ...user, username: 'admin', role: 'ADMIN',
    });
    const { container } = render(
      <MemoryRouter><AuthProvider><AdminLoginPage /></AuthProvider></MemoryRouter>,
    );
    const password = container.querySelector('input[type="password"]')!;

    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.password.required'))).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();

    fireEvent.change(password, { target: { value: 'x'.repeat(73) } });
    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.password.tooLong'))).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();
  });

  it('renderiza un dashboard vacío sin inventar actividad', async () => {
    vi.spyOn(accountApi, 'fetchDashboard').mockResolvedValue({
      account: user,
      counts: { bundles: 0, publicBundles: 0, privateBundles: 0, downloads: 0 },
      recentDownloads: [],
      recentBundles: [],
    });
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);

    expect(await screen.findByText(t('account.dashboard.title', { username: 'person' })))
      .toBeInTheDocument();
    expect(screen.getByText(t('account.downloads.empty'))).toBeInTheDocument();
    expect(screen.getByText(t('account.bundles.empty'))).toBeInTheDocument();
  });

  it('crea un bundle privado usando el catálogo disponible', async () => {
    vi.spyOn(catalogAppsApi, 'fetchApps').mockResolvedValue({
      data: [app], page: 1, pageSize: 60, total: 1,
    });
    const create = vi.spyOn(accountApi, 'createOwnBundle').mockResolvedValue({
      id: 'bundle-id', slug: 'my-bundle', name: 'My bundle', description: '',
      visibility: 'private', appCount: 1, tags: ['tools'], apps: [app],
      updatedAt: '2026-08-08T00:00:00Z', version: 0,
    });
    const { container } = render(
      <MemoryRouter initialEntries={['/dashboard/bundles/new']}>
        <Routes>
          <Route path="/dashboard/bundles/new" element={<BundleEditorPage />} />
          <Route path="/dashboard/bundles/:id/edit" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(catalogAppsApi.fetchApps).toHaveBeenCalledWith(
      expect.objectContaining({ filter: 'available' }), expect.any(AbortSignal),
    ));
    const textInputs = container.querySelectorAll('.bundle-fields input');
    fireEvent.change(textInputs[0], { target: { value: 'My bundle' } });
    fireEvent.change(textInputs[1], { target: { value: 'my-bundle' } });
    fireEvent.change(textInputs[2], { target: { value: 'tools' } });
    fireEvent.click(await screen.findByRole('checkbox', { name: /Sample App/ }));
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(create).toHaveBeenCalledWith({
      name: 'My bundle', description: '', slug: 'my-bundle', tags: ['tools'],
      appIds: [app.id],
    }));
    expect(await screen.findByTestId('location')).toHaveTextContent(
      '/dashboard/bundles/bundle-id/edit',
    );
  });

  it('renderiza actividad reciente y un fallo recuperable del dashboard', async () => {
    const dashboard = {
      account: user,
      counts: { bundles: 1, publicBundles: 1, privateBundles: 0, downloads: 2 },
      recentDownloads: [{
        appId: app.id, appName: app.name, iconUrl: 'https://example.com/icon.png',
        jobId: 'job-1', downloadedAt: '2026-08-24T10:00:00Z',
      }, {
        appId: 'app-2', appName: 'Without icon', jobId: 'job-2',
        downloadedAt: '2026-08-24T11:00:00Z',
      }],
      recentBundles: [{
        id: 'bundle-1', slug: 'tools', name: 'Tools', visibility: 'public' as const,
        appCount: 2, tags: [], updatedAt: '2026-08-24T10:00:00Z', version: 1,
      }],
    };
    const fetch = vi.spyOn(accountApi, 'fetchDashboard').mockResolvedValueOnce(dashboard)
      .mockRejectedValueOnce(new TypeError('offline'));
    const first = render(<MemoryRouter><DashboardPage /></MemoryRouter>);
    expect(await screen.findByText('Without icon')).toBeInTheDocument();
    expect(first.container.querySelector('img')).toHaveAttribute(
      'src', 'https://example.com/icon.png',
    );
    expect(screen.getByRole('link', { name: 'Tools' })).toHaveAttribute(
      'href', '/dashboard/bundles/bundle-1/edit',
    );
    first.unmount();
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);
    expect(await screen.findByText(t('account.dashboard.failed'))).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('lista bundles propios y muestra el error de carga', async () => {
    const bundle = {
      id: 'bundle-1', slug: 'tools', name: 'Tools', description: null,
      visibility: 'private' as const, appCount: 1, tags: [],
      updatedAt: '2026-08-24T10:00:00Z', version: 1,
    };
    vi.spyOn(accountApi, 'fetchOwnBundles')
      .mockResolvedValueOnce({ data: [bundle], page: 1, pageSize: 20, total: 1 })
      .mockRejectedValueOnce(new TypeError('offline'));
    const first = render(<MemoryRouter><AccountBundlesPage /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: 'Tools' })).toBeInTheDocument();
    first.unmount();
    render(<MemoryRouter><AccountBundlesPage /></MemoryRouter>);
    expect(await screen.findByText(t('account.bundles.failed'))).toBeInTheDocument();
  });

  it('edita, deselecciona y elimina un bundle existente con confirmación', async () => {
    const existing = {
      id: 'bundle-1', slug: 'tools', name: 'Tools', description: null,
      visibility: 'private' as const, appCount: 1, tags: ['dev'], apps: [app],
      updatedAt: '2026-08-24T10:00:00Z', version: 3,
    };
    vi.spyOn(accountApi, 'fetchOwnBundle').mockResolvedValue(existing);
    vi.spyOn(catalogAppsApi, 'fetchApps').mockResolvedValue({
      data: [app], page: 1, pageSize: 60, total: 1,
    });
    const update = vi.spyOn(accountApi, 'updateOwnBundle').mockResolvedValue({
      ...existing, visibility: 'public', apps: [], appCount: 0, version: 4,
    });
    const remove = vi.spyOn(accountApi, 'deleteOwnBundle').mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    const { container } = render(
      <MemoryRouter initialEntries={['/dashboard/bundles/bundle-1/edit']}>
        <Routes>
          <Route path="/dashboard/bundles/:id/edit" element={<BundleEditorPage />} />
          <Route path="/dashboard/bundles" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByDisplayValue('Tools')).toBeInTheDocument();
    const checkbox = await screen.findByRole('checkbox', { name: /Sample App/ });
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'public' } });
    fireEvent.submit(container.querySelector('form')!);
    await waitFor(() => expect(update).toHaveBeenCalledWith(
      'bundle-1',
      expect.objectContaining({ visibility: 'public', expectedVersion: 3, appIds: [] }),
    ));

    const deleteButton = screen.getByRole('button', { name: t('account.delete') });
    fireEvent.click(deleteButton);
    expect(remove).not.toHaveBeenCalled();
    fireEvent.click(deleteButton);
    await waitFor(() => expect(remove).toHaveBeenCalledWith('bundle-1'));
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(await screen.findByTestId('location')).toHaveTextContent('/dashboard/bundles');
  });

  it('actualiza el principal después de cambiar el username', async () => {
    vi.mocked(accountApi.me).mockResolvedValue(user);
    const changed = { ...user, username: 'renamed-person' };
    const update = vi.spyOn(accountApi, 'updateProfile').mockResolvedValue(changed);
    const { container } = render(
      <MemoryRouter><AuthProvider><ProfileAfterAuthentication /></AuthProvider></MemoryRouter>,
    );
    await waitFor(() => expect(container.querySelector('input[autocomplete="username"]')).not.toBeNull());
    const input = container.querySelector('input[autocomplete="username"]')!;
    fireEvent.change(input, { target: { value: 'renamed-person' } });
    fireEvent.submit(container.querySelector('form')!);
    await waitFor(() => expect(update).toHaveBeenCalledWith('renamed-person'));
    expect(await screen.findByText(t('account.profile.saved'))).toBeInTheDocument();
  });

  it('cierra sesión desde el layout de cuenta', async () => {
    vi.mocked(accountApi.me).mockResolvedValue(user);
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <Routes>
            <Route path="/dashboard" element={<AccountLayout />}>
              <Route index element={<p>Contenido</p>} />
            </Route>
            <Route path="/login" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByRole('button', { name: t('nav.logout') })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: t('nav.logout') }));
    expect(await screen.findByTestId('location')).toHaveTextContent('/login');
    expect(accountApi.logout).toHaveBeenCalledOnce();
  });
});
