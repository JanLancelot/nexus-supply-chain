import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Notification } from '../types';
import { deferredReply, serveApi } from '../test/http';
import { renderAuthenticated } from '../test/render';
import Header from './Header';

const notifications: Notification[] = [
  { id: 'read', userId: 'test-user', type: 'ORDER_UPDATE', message: 'Notice: already reviewed', isRead: true, createdAt: '2026-01-03T10:00:00Z' },
  { id: 'older', userId: 'test-user', type: 'LOW_STOCK', message: 'Notice: paper stock low', isRead: false, createdAt: '2026-01-01T10:00:00Z' },
  { id: 'newer', userId: 'test-user', type: 'ORDER_UPDATE', message: 'Notice: order approved', isRead: false, createdAt: '2026-01-02T10:00:00Z' },
];
const response = { notifications, totalCount: 3, unreadCount: 2 };

afterEach(() => vi.useRealTimers());

describe('notification center', () => {
  it('orders unread notices first, newest first, and closes on an outside click', async () => {
    serveApi({ 'GET /notifications': { data: response } });
    const user = userEvent.setup();
    renderAuthenticated(<Header title="Product Catalog" />);
    await screen.findByText('2');
    const toggle = screen.getByRole('button', { name: 'Notifications' });
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Notifications (Total: 3)')).toBeInTheDocument();
    expect(screen.getAllByText(/^Notice:/).map(element => element.textContent)).toEqual([
      'Notice: order approved', 'Notice: paper stock low', 'Notice: already reviewed',
    ]);
    await user.click(screen.getByRole('heading', { name: 'Product Catalog' }));
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Notice: order approved')).not.toBeInTheDocument();
  });

  it('marks one notification as read only after the server accepts it', async () => {
    const pending = deferredReply();
    const requests = serveApi({ 'GET /notifications': { data: response },
      'PUT /notifications/newer/read': () => pending.promise,
    });
    const user = userEvent.setup();
    renderAuthenticated(<Header title="Catalog" />);
    await screen.findByText('2');
    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    await user.click(screen.getAllByRole('button', { name: 'Mark as read' })[0]);
    expect(screen.getByRole('button', { name: 'Read All' })).toBeDisabled();
    expect(screen.getAllByRole('button', { name: 'Mark as read' }).every(button => button.hasAttribute('disabled'))).toBe(true);
    expect(screen.getByRole('button', { name: 'Notifications' })).toHaveTextContent('2');
    pending.resolve({ data: null });
    await screen.findByText('1');
    expect(screen.getAllByRole('button', { name: 'Mark as read' })).toHaveLength(1);
    expect(screen.getByText('Notice: order approved')).toBeInTheDocument();
    expect(requests.filter(request => request.method === 'PUT').map(request => request.url)).toEqual(['/notifications/newer/read']);
  });

  it('marks every notice as read and clears the badge', async () => {
    serveApi({ 'GET /notifications': { data: response }, 'PUT /notifications/read-all': { data: null } });
    const user = userEvent.setup();
    renderAuthenticated(<Header title="Catalog" />);
    await screen.findByText('2');
    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    await user.click(screen.getByRole('button', { name: 'Read All' }));
    expect(screen.getByRole('button', { name: 'Notifications' })).toHaveTextContent('');
    expect(screen.queryByRole('button', { name: 'Read All' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark as read' })).not.toBeInTheDocument();
    expect(screen.getAllByText(/^Notice:/)).toHaveLength(3);
  });

  it.each([
    ['single', '/notifications/newer/read', 'Unable to mark the notification as read.'],
    ['all', '/notifications/read-all', 'Unable to mark notifications as read.'],
  ])('preserves unread state after a failed %s read request', async (operation, path, message) => {
    serveApi({ 'GET /notifications': { data: response }, [`PUT ${path}`]: { status: 503, data: {} } });
    const user = userEvent.setup();
    renderAuthenticated(<Header title="Catalog" />);
    await screen.findByText('2');
    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    await user.click(operation === 'all' ? screen.getByRole('button', { name: 'Read All' }) : screen.getAllByRole('button', { name: 'Mark as read' })[0]);
    expect(await screen.findByRole('status')).toHaveTextContent(message);
    expect(screen.getByRole('button', { name: 'Notifications' })).toHaveTextContent('2');
    expect(screen.getAllByRole('button', { name: 'Mark as read' })).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Read All' })).toBeEnabled();
  });

  it('shows an empty inbox and refreshes it on the next poll, stopping polling on unmount', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    let data = { notifications: [] as Notification[], totalCount: 0, unreadCount: 0 };
    const requests = serveApi({ 'GET /notifications': () => ({ data }) });
    const user = userEvent.setup();
    const { unmount } = renderAuthenticated(<Header title="Catalog" />);
    await user.click(screen.getByRole('button', { name: 'Notifications' }));
    expect(screen.getByText('No notifications yet')).toBeInTheDocument();
    data = response;
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000); });
    expect(screen.getByText('Notice: paper stock low')).toBeInTheDocument();
    expect(screen.queryByText('No notifications yet')).not.toBeInTheDocument();
    unmount();
    const requestCount = requests.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000); });
    expect(requests).toHaveLength(requestCount);
  });
});
