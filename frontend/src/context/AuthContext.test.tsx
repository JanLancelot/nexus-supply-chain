import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import api from '../services/api';
import { getAccessToken, parseSession, setSession } from '../services/session';
import { sessionToken } from '../test/session';
import { AuthProvider } from './AuthContext';
import { useAuth } from './auth-context';

const renderSession = () => renderHook(() => useAuth(), { wrapper: AuthProvider });

describe('authentication session', () => {
  it('starts without a session and removes legacy persisted credentials', () => {
    localStorage.setItem('token', sessionToken());
    const { result } = renderSession();
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(localStorage.getItem('token')).toBeNull();
  });

  it.each([
    ['ROLE_ADMIN', true],
    ['ROLE_STAFF', false],
  ])('exposes the current %s session', (role, isAdmin) => {
    setSession(parseSession(sessionToken({ role })));
    const { result } = renderSession();
    expect(result.current).toMatchObject({
      isAuthenticated: true, isAdmin,
      user: { fullName: 'Test Operator', email: 'operator@example.com', role },
    });
  });

  it.each([
    ['expired', () => sessionToken({ exp: 1 })],
    ['malformed', () => 'invalid-token'],
  ])('rejects an %s login session', async (_name, token) => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { token: token() } });
    const { result } = renderSession();
    await expect(result.current.login('operator@example.com', 'test-password')).rejects.toThrow();
    expect(result.current.isAuthenticated).toBe(false);
    expect(getAccessToken()).toBeNull();
  });

  it('keeps login in memory and clears it on logout', async () => {
    const token = sessionToken({ role: 'ROLE_STAFF' });
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { token } });
    const { result } = renderSession();
    await act(() => result.current.login('operator@example.com', 'test-password'));
    expect(post).toHaveBeenCalledWith('/auth/login', {
      email: 'operator@example.com', password: 'test-password',
    });
    expect(localStorage.getItem('token')).toBeNull();
    expect(getAccessToken()).toBe(token);
    expect(result.current.user?.role).toBe('ROLE_STAFF');
    expect(result.current.isAuthenticated).toBe(true);

    act(() => result.current.logout());
    expect(getAccessToken()).toBeNull();
    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('surfaces a rejected login without creating a session', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({ isAxiosError: true, response: { data: { message: 'Account disabled' } } });
    const { result } = renderSession();
    await expect(result.current.login('operator@example.com', 'wrong-password'))
      .rejects.toThrow('Account disabled');
    expect(result.current.isAuthenticated).toBe(false);
    expect(getAccessToken()).toBeNull();
  });
});
