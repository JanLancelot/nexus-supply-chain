import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import api from './api';
import { getAccessToken, parseSession, setSession } from './session';
import { sessionToken } from '../test/session';

describe('API client', () => {
  it.each([null, sessionToken()])('sends the current session token: %s', async (token) => {
    if (token) setSession(parseSession(token));
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
    const token = sessionToken();
    setSession(parseSession(token));
    const adapter: AxiosAdapter = async (config) => {
      const response: AxiosResponse = { data: {}, status, statusText: 'Error', headers: {}, config };
      throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, response);
    };
    await expect(api.get('/inventory/products', { adapter })).rejects.toMatchObject({ response: { status } });
    expect(getAccessToken()).toBe(status === 401 ? null : token);
    expect(window.location.pathname).toBe('/login');
  });
});
