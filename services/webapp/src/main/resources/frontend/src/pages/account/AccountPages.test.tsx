import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../../api/account';
import * as catalogApi from '../../api/catalog';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import { t } from '../../services/i18n';
import type { AuthUser, CatalogApp } from '../../types/catalog';
import {
  BundleEditorPage,
  DashboardPage,
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
    vi.spyOn(catalogApi, 'me').mockResolvedValue(null);
    vi.spyOn(catalogApi, 'logout').mockResolvedValue(undefined);
    vi.spyOn(catalogApi, 'adminLogout').mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('logs a user in by email and preserves the protected destination', async () => {
    const login = vi.spyOn(catalogApi, 'login').mockResolvedValue(user);
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
    vi.spyOn(catalogApi, 'fetchApps').mockResolvedValue({
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

    await waitFor(() => expect(catalogApi.fetchApps).toHaveBeenCalledWith(
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
    vi.mocked(catalogApi.me).mockResolvedValue(user);
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
});
