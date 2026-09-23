import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { metrics } from '../test/fixtures';
import { serveApi } from '../test/http';
import { renderAuthenticated } from '../test/render';
import Dashboard from './Dashboard';

describe('operations dashboard', () => {
  it('presents monetary metrics, order proportions, product demand and warehouse stock', async () => {
    serveApi({ 'GET /analytics/dashboard': { data: metrics } });
    renderAuthenticated(<Dashboard />);
    expect(await screen.findByText('$1,250.50')).toBeInTheDocument();
    expect(screen.getByText('$7,425.00')).toBeInTheDocument();
    expect(screen.getByText('2 products are below their reorder levels.')).toBeInTheDocument();
    expect(screen.getByText('1 (25%)')).toBeInTheDocument();
    expect(screen.getByText('3 (75%)')).toBeInTheDocument();
    expect(screen.getByText('75 units')).toBeInTheDocument();
    expect(screen.getByText('400 units')).toBeInTheDocument();
    expect(screen.getByText('150 units')).toBeInTheDocument();
  });

  it('handles empty data without displaying invalid totals or percentages', async () => {
    serveApi({ 'GET /analytics/dashboard': { data: {
      totalRevenue: 0, totalInventoryValue: 0, lowStockCount: 0,
      orderStatusCounts: {}, warehouseStockCounts: {}, topProducts: [],
    } } });
    renderAuthenticated(<Dashboard />);
    expect(await screen.findByText('No products are below their reorder levels.')).toBeInTheDocument();
    expect(screen.getByText('No ordered products registered yet.')).toBeInTheDocument();
    expect(screen.getByText('No registered orders.')).toBeInTheDocument();
    expect(screen.getByText('No warehouse data available.')).toBeInTheDocument();
    expect(screen.getAllByText('$0.00')).toHaveLength(2);
    expect(screen.queryByText(/NaN|Infinity/)).not.toBeInTheDocument();
  });

  it('retries unavailable metrics and refreshes displayed data', async () => {
    let unavailable = true;
    let data = metrics;
    serveApi({ 'GET /analytics/dashboard': () => unavailable ? { status: 503, data: {} } : { data } });
    const user = userEvent.setup();
    renderAuthenticated(<Dashboard />);
    expect(await screen.findByText('Unable to load dashboard metrics. Please try again.')).toBeInTheDocument();
    unavailable = false;
    await user.click(screen.getByRole('button', { name: 'Retry Load' }));
    expect(await screen.findByText('2 products are below their reorder levels.')).toBeInTheDocument();
    data = { ...metrics, lowStockCount: 0 };
    await user.click(screen.getByRole('button', { name: 'Refresh Data' }));
    expect(await screen.findByText('No products are below their reorder levels.')).toBeInTheDocument();
    expect(screen.queryByText('Low Stock Warnings')).not.toBeInTheDocument();
  });
});
