import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import api from '../services/api';
import { sessionToken } from '../test/session';
import { AuthProvider, useAuth } from './AuthContext';

const renderSession = () => renderHook(() => useAuth(), { wrapper: AuthProvider });

describe('authentication session', () => {
  it('finishes loading without a saved session', () => {
    const { result } = renderSession();
    expect(result.current.loading).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
  });

  it.each([
    ['ROLE_ADMIN', true, false],
    ['ROLE_STAFF', false, true],
  ])('restores a valid %s session', (role, isAdmin, isStaff) => {
    localStorage.setItem('token', sessionToken({ role }));
    const { result } = renderSession();
    expect(result.current).toMatchObject({
      loading: false, isAuthenticated: true, isAdmin, isStaff,
      user: { fullName: 'Test Operator', email: 'operator@example.com', role },
    });
  });

  it.each([
    ['expired', () => sessionToken({ exp: 1 })],
    ['malformed', () => 'invalid-token'],
  ])('discards an %s session', (_name, token) => {
    localStorage.setItem('token', token());
    const { result } = renderSession();
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBeNull();
    expect(localStorage.getItem('token')).toBeNull();
  });

  it('persists a successful login and clears it on logout', async () => {
    const token = sessionToken({ role: 'ROLE_STAFF' });
    const post = vi.spyOn(api, 'post').mockResolvedValue({ data: { token } });
    const { result } = renderSession();
    await act(() => result.current.login('operator@example.com', 'test-password'));
    expect(post).toHaveBeenCalledWith('/auth/login', {
      email: 'operator@example.com', password: 'test-password',
    });
    expect(localStorage.getItem('token')).toBe(token);
    expect(result.current.isStaff).toBe(true);
    expect(result.current.isAuthenticated).toBe(true);

    act(() => result.current.logout());
    expect(localStorage.getItem('token')).toBeNull();
    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.isStaff).toBe(false);
  });

  it('surfaces a rejected login without creating a session', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(api, 'post').mockRejectedValue({ response: { data: { message: 'Account disabled' } } });
    const { result } = renderSession();
    await expect(result.current.login('operator@example.com', 'wrong-password'))
      .rejects.toBe('Account disabled');
    await waitFor(() => expect(result.current.isAuthenticated).toBe(false));
    expect(localStorage.getItem('token')).toBeNull();
  });
});
