import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import api from './api';

describe('API client', () => {
  it.each([null, 'session-token'])('sends the current session token: %s', async (token) => {
    if (token) localStorage.setItem('token', token);
    const adapter: AxiosAdapter = async (config) => {
      expect(config.baseURL).toBe('/api/v1');
      expect(config.headers.Authorization).toBe(token ? `Bearer ${token}` : undefined);
      return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config };
    };
    const response = await api.get('/inventory/products', { adapter });
    expect(response.data).toEqual({ ok: true });
  });

  it.each([401, 403, 500])('rejects HTTP %s and only clears unauthorized sessions', async (status) => {
    window.history.replaceState({}, '', '/login');
    localStorage.setItem('token', 'session-token');
    const adapter: AxiosAdapter = async (config) => {
      const response: AxiosResponse = { data: {}, status, statusText: 'Error', headers: {}, config };
      throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, response);
    };
    await expect(api.get('/inventory/products', { adapter })).rejects.toMatchObject({ response: { status } });
    expect(localStorage.getItem('token')).toBe(status === 401 ? null : 'session-token');
    expect(window.location.pathname).toBe('/login');
  });
});
