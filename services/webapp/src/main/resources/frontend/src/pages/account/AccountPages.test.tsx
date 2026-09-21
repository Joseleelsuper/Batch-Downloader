import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as accountApi from '../../api/account';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import { t } from '../../services/i18n';
import type { AuthUser } from '../../types/catalog';
import { AccountLayout, AdminLoginPage, ProfilePage, UserLoginPage } from './AccountPages';

const user: AuthUser = {
  id: '00000000-0000-0000-0000-000000000123',
  username: 'person',
  email: 'person@example.com',
  emailVerified: true,
  role: 'USER',
  createdAt: '2026-08-08T00:00:00Z',
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
    fireEvent.change(container.querySelector('input[type="email"]')!, {
      target: { value: 'person@example.com' },
    });
    fireEvent.submit(container.querySelector('form')!);

    await waitFor(() => expect(request).toHaveBeenCalledWith('person@example.com', 'es'));
    expect(await screen.findByText(t('account.magic.sent'))).toBeInTheDocument();
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
    const inputs = container.querySelectorAll('input');
    fireEvent.change(inputs[0], { target: { value: 'admin' } });
    fireEvent.change(inputs[1], { target: { value: 'secret' } });
    fireEvent.submit(container.querySelector('form')!);
    await waitFor(() => expect(login).toHaveBeenCalledWith('admin', 'secret'));
    expect(await screen.findByTestId('location')).toHaveTextContent('/admin');
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
