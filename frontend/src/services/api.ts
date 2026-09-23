import axios from 'axios';
import { getAccessToken, setSession } from './session.ts';

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    // Ignore an old request's 401 after another account has signed in.
    if (axios.isAxiosError(error) && error.response?.status === 401 &&
        error.config?.headers.Authorization === `Bearer ${getAccessToken()}`) {
      setSession(null);
    }
    return Promise.reject(error);
  },
);

export default api;
