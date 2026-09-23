import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { AuditLog } from '../types';
import { serveApi } from '../test/http';
import { selectWithOption } from '../test/render';
import AuditLogs from './AuditLogs';

const productLog: AuditLog = {
  id: 'audit-1', userId: 'operator@example.com', entityType: 'Product', entityId: 'product-1',
  action: 'ACTION_MANUAL_ADJUSTMENT', oldValue: '{"stockQuantity":30,"name":"Hand Soap"}',
  newValue: '{"stockQuantity":25,"name":"Hand Soap","reasonCode":"DAMAGED_GOODS_SCRAP"}',
  createdAt: '2026-01-01T10:00:00Z',
};
const orderLog: AuditLog = {
  id: 'audit-2', userId: 'buyer@example.com', entityType: 'Order', entityId: 'order-1',
  action: 'ACTION_CREATE_ORDER', oldValue: '', newValue: '{"status":"DRAFT"}',
  createdAt: '2026-01-02T10:00:00Z',
};

describe('audit history', () => {
  it('sorts newest first and combines actor, entity and action filters', async () => {
    serveApi({ 'GET /audit-logs?page=0&size=50': { data: [productLog, orderLog] } });
    const user = userEvent.setup();
    render(<AuditLogs />);
    await screen.findByText('ID: product-1');
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('ID: order-1');
    await user.selectOptions(selectWithOption('All Entities'), 'Product');
    await user.selectOptions(selectWithOption('All Actions'), 'ACTION_MANUAL_ADJUSTMENT');
    await user.type(screen.getByPlaceholderText('Search IDs on this page...'), 'OPERATOR');
    expect(screen.getByRole('row', { name: /ID: product-1/ })).toBeInTheDocument();
    expect(screen.queryByText('ID: order-1')).not.toBeInTheDocument();
    await user.selectOptions(selectWithOption('All Actions'), 'ACTION_CREATE_ORDER');
    expect(screen.getByText('No Audit Logs Recorded')).toBeInTheDocument();
  });

  it('inspects changed, unchanged and newly added properties in an audit snapshot', async () => {
    serveApi({ 'GET /audit-logs?page=0&size=50': { data: [productLog] } });
    const user = userEvent.setup();
    render(<AuditLogs />);
    await user.click(await screen.findByRole('row', { name: /ID: product-1/ }));
    expect(screen.getByText('Log Snapshot Details')).toBeInTheDocument();
    const stock = screen.getByRole('row', { name: /stockQuantity/ });
    expect(within(stock).getAllByRole('cell').map(cell => cell.textContent)).toEqual(['stockQuantity', '30', '25']);
    expect(within(screen.getByRole('row', { name: /^name / })).getAllByRole('cell').map(cell => cell.textContent))
      .toEqual(['name', '"Hand Soap"', '"Hand Soap"']);
    expect(within(screen.getByRole('row', { name: /reasonCode/ })).getAllByRole('cell').map(cell => cell.textContent))
      .toEqual(['reasonCode', 'NULL', '"DAMAGED_GOODS_SCRAP"']);
  });

  it.each([
    ['legacy text', 'not-json', '"not-json"'],
    ['array', '[1,2]', '[1,2]'],
    ['null', 'null', 'null'],
  ])('renders a %s audit value safely', async (_kind, value, displayed) => {
    serveApi({ 'GET /audit-logs?page=0&size=50': { data: [{ ...orderLog, newValue: value }] } });
    const user = userEvent.setup();
    render(<AuditLogs />);
    await user.click(await screen.findByRole('row', { name: /ID: order-1/ }));
    expect(within(screen.getByRole('row', { name: /^value / })).getAllByRole('cell').map(cell => cell.textContent))
      .toEqual(['value', 'NULL', displayed]);
  });

  it('shows a useful message for a snapshot without recorded properties', async () => {
    serveApi({ 'GET /audit-logs?page=0&size=50': { data: [{ ...orderLog, oldValue: '', newValue: '{}' }] } });
    const user = userEvent.setup();
    render(<AuditLogs />);
    await user.click(await screen.findByRole('row', { name: /ID: order-1/ }));
    expect(screen.getByText('No structural properties recorded.')).toBeInTheDocument();
  });

  it('recovers after a failed request and navigates to the last audit page', async () => {
    let unavailable = true;
    const firstPage = Array.from({ length: 50 }, (_, index) => ({ ...productLog, id: `audit-${index}`, entityId: `product-${index}` }));
    serveApi({
      'GET /audit-logs?page=0&size=50': () => unavailable ? { status: 503, data: {} } : { data: firstPage },
      'GET /audit-logs?page=1&size=50': { data: [orderLog] },
    });
    const user = userEvent.setup();
    render(<AuditLogs />);
    expect(await screen.findByText('Unable to load audit logs. Please try again.')).toBeInTheDocument();
    unavailable = false;
    await user.click(screen.getByRole('button', { name: 'Refresh Audit History' }));
    await screen.findByText('ID: product-49');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('ID: order-1')).toBeInTheDocument();
    expect(screen.queryByText('ID: product-49')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
  });
});
