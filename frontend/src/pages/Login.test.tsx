import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../context/AuthContext';
import api from '../services/api';
import { sessionToken } from '../test/session';
import Login from './Login';

function renderLogin() {
  render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<h1>Signed in</h1>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
  return userEvent.setup();
}

describe('login form', () => {
  it('submits credentials and navigates after authentication', async () => {
    vi.spyOn(api, 'post').mockResolvedValue({ data: { token: sessionToken() } });
    const user = renderLogin();
    await user.type(screen.getByLabelText('Email Address'), 'operator@example.com');
    await user.type(screen.getByLabelText('Password'), 'test-password');
    await user.click(screen.getByRole('button', { name: 'Sign In' }));
    expect(await screen.findByRole('heading', { name: 'Signed in' })).toBeInTheDocument();
  });

  it('shows the server error and lets the user retry', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(api, 'post').mockRejectedValue({ isAxiosError: true, response: { data: { message: 'Invalid credentials' } } });
    const user = renderLogin();
    await user.type(screen.getByLabelText('Email Address'), 'operator@example.com');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign In' }));
    expect(await screen.findByText('Invalid credentials')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign In' })).toBeEnabled();
    expect(localStorage.getItem('token')).toBeNull();
  });
});
