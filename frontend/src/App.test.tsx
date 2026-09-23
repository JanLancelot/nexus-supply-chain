import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { getAccessToken, parseSession, setSession } from './services/session';
import { catalogResponses, metrics } from './test/fixtures';
import { serveApi } from './test/http';
import { sessionToken } from './test/session';
import App from './App';

const authenticatedResponses = {
  ...catalogResponses,
  'GET /notifications': { data: { notifications: [], totalCount: 0, unreadCount: 0 } },
  'GET /analytics/dashboard': { data: metrics },
};

describe('application routing and navigation', () => {
  it.each(['/dashboard', '/catalog', '/orders', '/users', '/audit-logs'])('requires a session before rendering %s', async (path) => {
    const requests = serveApi({});
    window.history.replaceState({}, '', path);
    render(<App />);
    expect(await screen.findByRole('button', { name: 'Sign In' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');
    expect(requests).toHaveLength(0);
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it.each(['/dashboard', '/users', '/audit-logs'])('redirects staff away from %s and hides administrator navigation', async (path) => {
    serveApi(authenticatedResponses);
    setSession(parseSession(sessionToken({ role: 'ROLE_STAFF' })));
    window.history.replaceState({}, '', path);
    render(<App />);
    expect(await screen.findByText('Hand Soap')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/catalog');
    expect(screen.getByRole('heading', { name: 'Product Catalog' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'User Management' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit Logs' })).not.toBeInTheDocument();
  });

  it.each(['/', '/login', '/unknown'])('lands an authenticated administrator on the dashboard from %s', async (path) => {
    serveApi(authenticatedResponses);
    setSession(parseSession(sessionToken()));
    window.history.replaceState({}, '', path);
    render(<App />);
    expect(await screen.findByText('75 units')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
    expect(screen.getByRole('heading', { name: 'Operations Dashboard' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'User Management' })).toBeInTheDocument();
  });

  it('navigates through the sidebar and removes protected content when signing out', async () => {
    serveApi(authenticatedResponses);
    setSession(parseSession(sessionToken()));
    const user = userEvent.setup();
    render(<App />);
    await screen.findByText('75 units');
    await user.click(screen.getByRole('link', { name: 'Product Catalog' }));
    expect(await screen.findByText('Hand Soap')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/catalog');
    await user.click(screen.getByRole('button', { name: 'Sign Out' }));
    expect(await screen.findByRole('button', { name: 'Sign In' })).toBeInTheDocument();
    expect(screen.queryByText('Hand Soap')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });
});
