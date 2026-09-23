import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { User } from '../types';
import { users } from '../test/fixtures';
import { deferredReply, serveApi } from '../test/http';
import { selectWithOption } from '../test/render';
import UserManagement from './UserManagement';

describe('user management', () => {
  it('sorts the directory and combines role and email filters', async () => {
    serveApi({ 'GET /users': { data: users } });
    const user = userEvent.setup();
    render(<UserManagement />);
    await screen.findByText('Zoe Staff');
    const rows = screen.getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Ari Admin');
    expect(rows[2]).toHaveTextContent('Zoe Staff');
    await user.selectOptions(selectWithOption('All Roles'), 'ROLE_STAFF');
    expect(screen.queryByText('Ari Admin')).not.toBeInTheDocument();
    const search = screen.getByPlaceholderText('Search by name or email...');
    await user.type(search, 'ARI@EXAMPLE.COM');
    expect(screen.getByText('No Users Found')).toBeInTheDocument();
    await user.selectOptions(selectWithOption('All Roles'), 'ALL');
    expect(screen.getByRole('row', { name: /Ari Admin/ })).toBeInTheDocument();
    expect(screen.queryByText('Zoe Staff')).not.toBeInTheDocument();
  });

  it('validates required fields, email format and password length before sending a request', async () => {
    const requests = serveApi({ 'GET /users': { data: users } });
    const user = userEvent.setup();
    render(<UserManagement />);
    await screen.findByText('Ari Admin');
    await user.click(screen.getByRole('button', { name: 'Create User Account' }));
    expect(screen.getByText('Full name is required')).toBeInTheDocument();
    expect(screen.getByText('Email address is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText('e.g. Jane Doe'), 'New Staff');
    await user.type(screen.getByPlaceholderText('name@company.com'), 'invalid');
    await user.type(screen.getByPlaceholderText('Min 8 characters'), 'short');
    await user.click(screen.getByRole('button', { name: 'Create User Account' }));
    expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument();
    expect(screen.getByText('Password must be at least 8 characters long')).toBeInTheDocument();
    expect(screen.queryByText('Full name is required')).not.toBeInTheDocument();
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(0);
  });

  it('rejects a multibyte password exceeding the bcrypt byte limit', async () => {
    const requests = serveApi({ 'GET /users': { data: [] } });
    const user = userEvent.setup();
    render(<UserManagement />);
    await user.type(screen.getByPlaceholderText('e.g. Jane Doe'), 'New Staff');
    await user.type(screen.getByPlaceholderText('name@company.com'), 'new@example.com');
    await user.type(screen.getByPlaceholderText('Min 8 characters'), 'é'.repeat(37));
    await user.click(screen.getByRole('button', { name: 'Create User Account' }));
    expect(screen.getByText('Password must be at most 72 UTF-8 bytes')).toBeInTheDocument();
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(0);
  });

  it('normalizes identity fields, refreshes the directory and resets the form after creation', async () => {
    let directory: User[] = [...users];
    const pending = deferredReply();
    const requests = serveApi({
      'GET /users': () => ({ data: directory }),
      'POST /users': () => pending.promise,
    });
    const user = userEvent.setup();
    render(<UserManagement />);
    await screen.findByText('Ari Admin');
    await user.type(screen.getByPlaceholderText('e.g. Jane Doe'), '  Bea Buyer  ');
    await user.type(screen.getByPlaceholderText('name@company.com'), 'BEA@EXAMPLE.COM');
    const password = 'x'.repeat(12);
    await user.type(screen.getByPlaceholderText('Min 8 characters'), password);
    await user.selectOptions(selectWithOption('Administrator'), 'ROLE_ADMIN');
    await user.click(screen.getByRole('button', { name: 'Create User Account' }));
    expect(screen.getByRole('button', { name: 'Creating Account...' })).toBeDisabled();
    expect(requests.find(request => request.method === 'POST')?.body).toEqual({
      fullName: 'Bea Buyer', email: 'bea@example.com', password, roleName: 'ROLE_ADMIN',
    });
    const created: User = { id: 'user-3', fullName: 'Bea Buyer', email: 'bea@example.com', role: 'ROLE_ADMIN', status: 'ACTIVE' };
    directory = [...users, created];
    pending.resolve({ data: created });
    expect(await screen.findByRole('row', { name: /Bea Buyer/ })).toHaveTextContent('bea@example.com');
    expect(screen.getByPlaceholderText('e.g. Jane Doe')).toHaveValue('');
    expect(screen.getByPlaceholderText('name@company.com')).toHaveValue('');
    expect(screen.getByPlaceholderText('Min 8 characters')).toHaveValue('');
    expect(selectWithOption('Administrator')).toHaveValue('ROLE_STAFF');
  });

  it('preserves form input and shows the server rejection', async () => {
    serveApi({ 'GET /users': { data: users },
      'POST /users': { status: 409, data: { message: 'Email is already registered' } },
    });
    const user = userEvent.setup();
    render(<UserManagement />);
    await user.type(screen.getByPlaceholderText('e.g. Jane Doe'), 'New Staff');
    await user.type(screen.getByPlaceholderText('name@company.com'), 'ari@example.com');
    await user.type(screen.getByPlaceholderText('Min 8 characters'), 'x'.repeat(12));
    await user.click(screen.getByRole('button', { name: 'Create User Account' }));
    expect(await screen.findByText('Email is already registered')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('name@company.com')).toHaveValue('ari@example.com');
    expect(screen.getByRole('button', { name: 'Create User Account' })).toBeEnabled();
    expect(screen.queryByText(/successfully created/)).not.toBeInTheDocument();
  });

  it('retries a failed directory load', async () => {
    let unavailable = true;
    serveApi({ 'GET /users': () => unavailable ? { status: 503, data: {} } : { data: users } });
    const user = userEvent.setup();
    render(<UserManagement />);
    expect(await screen.findByText('Unable to load users. Please try again.')).toBeInTheDocument();
    unavailable = false;
    await user.click(screen.getByRole('button', { name: 'Refresh Directory' }));
    expect(await screen.findByText('Ari Admin')).toBeInTheDocument();
    expect(screen.queryByText('Unable to load users. Please try again.')).not.toBeInTheDocument();
  });
});
