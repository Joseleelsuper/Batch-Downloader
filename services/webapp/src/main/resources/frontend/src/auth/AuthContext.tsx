import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { adminLogout, logout, me } from '../api/catalog';
import type { AuthUser } from '../types/catalog';

export type AuthStatus = 'checking' | 'anonymous' | 'authenticated';

interface AuthContextValue {
  status: AuthStatus;
  account: AuthUser | null;
  setAuthenticated: (account: AuthUser) => void;
  refresh: () => Promise<AuthUser | null>;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const AUTH_SCOPE_KEY = 'batch-downloader.account-scope.v1';
const DOWNLOAD_JOBS_KEY = 'batch-downloader.download-jobs.v1';

export function AuthProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [status, setStatus] = useState<AuthStatus>('checking');
  const [account, setAccount] = useState<AuthUser | null>(null);

  const applyAccount = useCallback((next: AuthUser | null) => {
    setAccount(next);
    setStatus(next ? 'authenticated' : 'anonymous');
  }, []);

  const refresh = useCallback(async () => {
    try {
      const current = await me();
      applyAccount(current);
      return current;
    } catch {
      applyAccount(null);
      return null;
    }
  }, [applyAccount]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (status === 'checking') return;
    const nextScope = account?.id ?? 'anonymous';
    try {
      const previousScope = window.sessionStorage.getItem(AUTH_SCOPE_KEY);
      const enteredAccountWithoutScope = !previousScope && nextScope !== 'anonymous';
      if ((previousScope && previousScope !== nextScope) || enteredAccountWithoutScope) {
        window.sessionStorage.removeItem(DOWNLOAD_JOBS_KEY);
      }
      window.sessionStorage.setItem(AUTH_SCOPE_KEY, nextScope);
    } catch {
      // El aislamiento principal sigue dependiendo del backend por UUID.
    }
  }, [account?.id, status]);

  const signOut = useCallback(async () => {
    const operation = account?.role === 'ADMIN' ? adminLogout : logout;
    await operation().catch(() => undefined);
    applyAccount(null);
  }, [account?.role, applyAccount]);

  const value = useMemo<AuthContextValue>(() => ({
    status,
    account,
    setAuthenticated: (next) => applyAccount(next),
    refresh,
    signOut,
  }), [account, applyAccount, refresh, signOut, status]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth debe utilizarse dentro de AuthProvider');
  return context;
}
