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
  ForgotPasswordPage,
  ProfilePage,
  RegisterPage,
  ResetPasswordPage,
  UserLoginPage,
  VerifyEmailPage,
} from './AccountPages';

const user: AuthUser = {
  id: '00000000-0000-0000-0000-000000000123',
  username: 'person',
  email: 'person@example.com',
  emailVerified: true,
  role: 'USER',
  notifyOnJobCompletion: true,
  createdAt: '2026-08-08T00:00:00Z',
  authenticationMethods: ['LOCAL'],
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
  return <output data-testid="location">{location.pathname}{location.search}</output>;
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
    vi.useRealTimers();
  });

  it('logs a user in by email and preserves the protected destination', async () => {
    const login = vi.spyOn(accountApi, 'login').mockResolvedValue(user);
    const { container } = render(
      <MemoryRouter initialEntries={[{
        pathname: '/login',
        state: { from: { pathname: '/dashboard/bundles', search: '?sort=recent' } },
      }]}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<UserLoginPage />} />
            <Route path="/dashboard/bundles" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(container.querySelector('img.google-auth-icon'))
      .toHaveAttribute('src', '/assets/google-g.png');

    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'Person@Example.com' },
    });
    fireEvent.change(container.querySelector('input[type="password"]')!, {
      target: { value: 'correct-password' },
    });
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(login).toHaveBeenCalledWith(
      'Person@Example.com', 'correct-password',
    ));
    expect(await screen.findByTestId('location')).toHaveTextContent(
      '/dashboard/bundles?sort=recent',
    );
  });

  it('blocks a registration when frontend password confirmation differs', () => {
    const register = vi.spyOn(accountApi, 'registerAccount').mockResolvedValue(user);
    const { container } = render(
      <MemoryRouter><RegisterPage /></MemoryRouter>,
    );
    const passwords = container.querySelectorAll('input[type="password"]');
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.change(passwords[0], { target: { value: 'long-password-one' } });
    fireEvent.change(passwords[1], { target: { value: 'long-password-two' } });
    fireEvent.submit(container.querySelector('form')!);

    expect(register).not.toHaveBeenCalled();
    expect(screen.getByText(t('account.password.mismatch'))).toBeInTheDocument();
  });

  it('removes a verification token from the URL before completing the request', async () => {
    const confirm = vi.spyOn(accountApi, 'confirmEmail').mockResolvedValue(undefined);
    render(
      <MemoryRouter initialEntries={['/verify-email?token=secret-verification-token']}>
        <Routes>
          <Route path="/verify-email" element={<><VerifyEmailPage /><LocationProbe /></>} />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(confirm).toHaveBeenCalledWith('secret-verification-token'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/verify-email'));
    expect(screen.getByTestId('location')).not.toHaveTextContent('token=');
    expect(await screen.findByText(t('account.verify.success'))).toBeInTheDocument();
  });

  it('keeps the reset token only in memory until the new password is submitted', async () => {
    const reset = vi.spyOn(accountApi, 'resetPassword').mockResolvedValue(undefined);
    const { container } = render(
      <MemoryRouter initialEntries={['/reset-password?token=secret-reset-token']}>
        <Routes>
          <Route path="/reset-password" element={<><ResetPasswordPage /><LocationProbe /></>} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/reset-password'));
    expect(screen.getByTestId('location')).not.toHaveTextContent('token=');
    const passwords = container.querySelectorAll('input[type="password"]');
    fireEvent.change(passwords[0], { target: { value: 'new-secure-password' } });
    fireEvent.change(passwords[1], { target: { value: 'new-secure-password' } });
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(reset).toHaveBeenCalledWith(
      'secret-reset-token', 'new-secure-password',
    ));
    expect(await screen.findByText(t('account.reset.success'))).toBeInTheDocument();
  });

  it('renders an empty dashboard without inventing history', async () => {
    vi.spyOn(accountApi, 'fetchDashboard').mockResolvedValue({
      account: user,
      counts: { bundles: 0, publicBundles: 0, privateBundles: 0, downloads: 0 },
      recentDownloads: [],
      recentBundles: [],
    });
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);

    expect(await screen.findByText(t('account.dashboard.title', { username: 'person' })))
      .toBeInTheDocument();
    expect(screen.getAllByText(t('account.downloads.empty'))).toHaveLength(1);
    expect(screen.getAllByText(t('account.bundles.empty'))).toHaveLength(1);
  });

  it('creates a private-by-default bundle using only the public available catalog', async () => {
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

  it('updates the in-memory principal after a username change', async () => {
    vi.mocked(accountApi.me).mockResolvedValue(user);
    const changed = { ...user, username: 'renamed-person' };
    const update = vi.spyOn(accountApi, 'updateProfile').mockResolvedValue(changed);
    const { container } = render(
      <MemoryRouter><AuthProvider><ProfileAfterAuthentication /></AuthProvider></MemoryRouter>,
    );

    await waitFor(() => expect(
      container.querySelector('input[autocomplete="username"]'),
    ).not.toBeNull());
    const input = container.querySelector('input[autocomplete="username"]')!;
    fireEvent.change(input, { target: { value: 'renamed-person' } });
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(update).toHaveBeenCalledWith('renamed-person'));
    expect(await screen.findByText(t('account.profile.saved'))).toBeInTheDocument();
  });

  it('muestra OAuth fallido, traduce credenciales inválidas y usa un destino seguro', async () => {
    const login = vi.spyOn(accountApi, 'login')
      .mockRejectedValueOnce(new ApiRequestError(401, 'invalid_credentials'))
      .mockResolvedValueOnce(user);
    const { container } = render(
      <MemoryRouter initialEntries={[{
        pathname: '/login',
        search: '?oauthError=provider',
        state: { from: { pathname: '//evil.example', search: '?token=leak' } },
      }]}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<UserLoginPage />} />
            <Route path="/dashboard" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(screen.getByText(t('account.login.oauthError'))).toBeInTheDocument();
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.change(container.querySelector('input[type="password"]')!, {
      target: { value: 'wrong' },
    });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText('El correo o la contraseña no son correctos.'))
      .toBeInTheDocument();

    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByTestId('location')).toHaveTextContent('/dashboard');
    expect(login).toHaveBeenCalledTimes(2);
  });

  it('autentica al administrador y recupera un error de transporte', async () => {
    const admin = { ...user, username: 'admin', role: 'ADMIN' as const };
    const login = vi.spyOn(accountApi, 'adminLogin')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(admin);
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
    const inputs = container.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'admin' } });
    fireEvent.change(inputs[1], { target: { value: 'secret' } });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText(t('login.invalid'))).toBeInTheDocument();
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByTestId('location')).toHaveTextContent('/admin');
    expect(login).toHaveBeenCalledTimes(2);
  });

  it('valida longitud y muestra errores de campo antes de registrar', async () => {
    const register = vi.spyOn(accountApi, 'registerAccount')
      .mockRejectedValueOnce(new ApiRequestError(422, 'validation_failed', null, {
        fieldErrors: { email: ['El correo ya está en uso.'] },
      }))
      .mockResolvedValueOnce(user);
    const { container } = render(
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/verify-email" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>,
    );
    const email = container.querySelector('input[type="email"]')!;
    const passwords = container.querySelectorAll('input[type="password"]');
    fireEvent.change(email, { target: { value: 'person@example.com' } });
    fireEvent.change(passwords[0], { target: { value: 'x'.repeat(73) } });
    fireEvent.change(passwords[1], { target: { value: 'x'.repeat(73) } });
    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.password.tooLong'))).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();

    fireEvent.change(passwords[0], { target: { value: 'secure-password' } });
    fireEvent.change(passwords[1], { target: { value: 'secure-password' } });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText('El correo ya está en uso.')).toBeInTheDocument();
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByTestId('location')).toHaveTextContent('/verify-email');
  });

  it('permite reenviar una verificación y recuperarse de un token inválido', async () => {
    vi.spyOn(accountApi, 'confirmEmail').mockRejectedValue(
      new ApiRequestError(400, 'verification_token_invalid'),
    );
    const resend = vi.spyOn(accountApi, 'resendVerification')
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(undefined);
    const { container } = render(
      <MemoryRouter initialEntries={['/verify-email?token=invalid']}>
        <Routes><Route path="/verify-email" element={<VerifyEmailPage />} /></Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText('El enlace de verificación no es válido.')).toBeInTheDocument();
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText(t('account.verify.resendFailed'))).toBeInTheDocument();
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText(t('account.verify.resent'))).toBeInTheDocument();
    expect(resend).toHaveBeenCalledTimes(2);
  });

  it('gestiona solicitud y errores de recuperación de contraseña', async () => {
    const request = vi.spyOn(accountApi, 'requestPasswordReset')
      .mockRejectedValueOnce(new ApiRequestError(503, 'service_busy'))
      .mockResolvedValueOnce(undefined);
    const { container } = render(<MemoryRouter><ForgotPasswordPage /></MemoryRouter>);
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText('El servicio está ocupado. Inténtalo de nuevo.'))
      .toBeInTheDocument();
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText(t('account.forgot.sent'))).toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('rechaza reset sin token, contraseñas distintas, excesivas y token caducado', async () => {
    const missing = render(<MemoryRouter><ResetPasswordPage /></MemoryRouter>);
    expect(screen.getByText(t('account.reset.missingToken'))).toBeInTheDocument();
    missing.unmount();

    vi.spyOn(accountApi, 'resetPassword').mockRejectedValue(
      new ApiRequestError(400, 'reset_token_expired'),
    );
    const { container } = render(
      <MemoryRouter initialEntries={['/reset-password?token=reset-token']}>
        <Routes><Route path="/reset-password" element={<ResetPasswordPage />} /></Routes>
      </MemoryRouter>,
    );
    const passwords = container.querySelectorAll('input[type="password"]');
    fireEvent.change(passwords[0], { target: { value: 'one-password' } });
    fireEvent.change(passwords[1], { target: { value: 'other-password' } });
    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.password.mismatch'))).toBeInTheDocument();

    fireEvent.change(passwords[0], { target: { value: 'x'.repeat(73) } });
    fireEvent.change(passwords[1], { target: { value: 'x'.repeat(73) } });
    fireEvent.submit(container.querySelector('form')!);
    expect(screen.getByText(t('account.password.tooLong'))).toBeInTheDocument();

    fireEvent.change(passwords[0], { target: { value: 'secure-password' } });
    fireEvent.change(passwords[1], { target: { value: 'secure-password' } });
    fireEvent.submit(container.querySelector('form')!);
    expect(await screen.findByText('El enlace de recuperación ha caducado.')).toBeInTheDocument();
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

  it('cierra sesión desde el layout de cuenta', async () => {
    vi.mocked(accountApi.me).mockResolvedValue(user);
    const { container } = render(
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
    await waitFor(() => expect(container.querySelector('.account-shell')).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: t('nav.logout') }));
    expect(await screen.findByTestId('location')).toHaveTextContent('/login');
    expect(accountApi.logout).toHaveBeenCalledOnce();
  });
});
