import { useEffect, useSyncExternalStore, type ReactNode } from 'react';
import api from '../services/api';
import { getErrorMessage } from '../services/errors';
import { getAccessToken, getSession, parseSession, setSession, subscribeSession } from '../services/session';
import { AuthContext } from './auth-context';

export const AuthProvider = ({ children }: { children: ReactNode }) => {
  const session = useSyncExternalStore(subscribeSession, getSession);

  useEffect(() => {
    // Remove tokens left behind by versions that persisted credentials.
    try { localStorage.removeItem('token'); } catch { /* Storage may be disabled. */ }
    const checkExpiry = () => { getAccessToken(); };
    window.addEventListener('focus', checkExpiry);
    return () => window.removeEventListener('focus', checkExpiry);
  }, []);

  const login = async (email: string, password: string) => {
    try {
      const response = await api.post<{ token: unknown }>('/auth/login', { email, password });
      const next = parseSession(response.data.token);
      if (!next) throw new Error('Invalid session returned by the server.');
      setSession(next);
    } catch (error: unknown) {
      // Axios error causes contain the submitted password; expose only the safe message.
      // eslint-disable-next-line preserve-caught-error
      throw new Error(getErrorMessage(error, 'Unable to sign in. Check your email and password.'));
    }
  };

  return (
    <AuthContext.Provider value={{
      user: session?.user ?? null,
      login,
      logout: () => setSession(null),
      isAuthenticated: session !== null,
      isAdmin: session?.user.role === 'ROLE_ADMIN',
    }}>
      {children}
    </AuthContext.Provider>
  );
};
