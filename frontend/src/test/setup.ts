import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { setSession } from '../services/session';

afterEach(() => {
  cleanup();
  setSession(null);
  localStorage.clear();
  window.history.replaceState({}, '', '/');
});
