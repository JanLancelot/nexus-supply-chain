import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { catalogResponses, pageOf, products } from '../test/fixtures';
import { deferredReply, serveApi } from '../test/http';
import { renderAuthenticated, selectWithOption } from '../test/render';
import Catalog from './Catalog';

describe('catalog', () => {
  it('combines SKU search, category and low-stock filters and recovers from an empty result', async () => {
    serveApi(catalogResponses);
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.selectOptions(selectWithOption('All Categories'), 'Household');
    await user.selectOptions(selectWithOption('All Stock'), 'LOW_STOCK');
    expect(screen.getByRole('row', { name: /PAPER-02/ })).toHaveTextContent('LOW STOCK');
    expect(screen.queryByText('Hand Soap')).not.toBeInTheDocument();
    const search = screen.getByPlaceholderText('Search this page by SKU or name...');
    await user.type(search, 'soap');
    expect(screen.getByRole('heading', { name: 'No Products Found' })).toBeInTheDocument();
    await user.clear(search);
    await user.type(search, 'paper-02');
    expect(within(screen.getByRole('table')).getByText('Paper Towels')).toBeInTheDocument();
  });

  it('lets staff read the catalog without exposing stock or product mutation controls', async () => {
    serveApi(catalogResponses);
    renderAuthenticated(<Catalog />, 'ROLE_STAFF');
    expect(await screen.findByText('Hand Soap')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New Product' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Adjust' })).not.toBeInTheDocument();
  });

  it('recovers from a failed load when the user refreshes', async () => {
    let unavailable = true;
    serveApi({ ...catalogResponses,
      'GET /inventory/products?page=0&size=50': () => unavailable
        ? { status: 503, data: {} } : { data: pageOf(products) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    expect(await screen.findByText('Unable to load products. Please try again.')).toBeInTheDocument();
    unavailable = false;
    await user.click(screen.getByRole('button', { name: 'Refresh Catalog Data' }));
    expect(await screen.findByText('Hand Soap')).toBeInTheDocument();
    expect(screen.queryByText('Unable to load products. Please try again.')).not.toBeInTheDocument();
  });

  it('moves between pages and disables navigation at both ends', async () => {
    const requests = serveApi({ ...catalogResponses,
      'GET /inventory/products?page=0&size=50': { data: pageOf([products[0]], 0, true) },
      'GET /inventory/products?page=1&size=50': { data: pageOf([products[1]], 1) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Paper Towels')).toBeInTheDocument();
    expect(screen.queryByText('Hand Soap')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('Hand Soap')).toBeInTheDocument();
    expect(requests.filter(request => request.url.startsWith('/inventory/products')).map(request => request.url))
      .toEqual(['/inventory/products?page=0&size=50', '/inventory/products?page=1&size=50', '/inventory/products?page=0&size=50']);
  });

  it('creates a product and prevents another submission while the request is pending', async () => {
    const pending = deferredReply();
    const requests = serveApi({ ...catalogResponses,
      'POST /inventory/products': () => pending.promise,
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'New Product' }));
    await user.type(screen.getByPlaceholderText('e.g. PG-TIDE-002'), 'BAG-03');
    await user.type(screen.getByPlaceholderText('e.g. Tide Pods Clean Breeze 38ct'), 'Reusable Bag');
    await user.type(screen.getByPlaceholderText('e.g. 12.99'), '4.50');
    await user.selectOptions(selectWithOption('Select Category'), 'home');
    await user.selectOptions(selectWithOption('Select Warehouse'), 'warehouse-1');
    await user.click(screen.getByRole('button', { name: 'Create Product' }));
    expect(screen.getByRole('button', { name: 'Creating...' })).toBeDisabled();
    expect(requests.find(request => request.method === 'POST')?.body).toEqual({
      sku: 'BAG-03', name: 'Reusable Bag', unitPrice: 4.5, reorderLevel: 10,
      categoryId: 'home', warehouseId: 'warehouse-1',
    });
    pending.resolve({ data: { ...products[0], id: 'product-3', sku: 'BAG-03', name: 'Reusable Bag', unitPrice: 4.5 } });
    expect(await screen.findByText('Successfully cataloged product: BAG-03')).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /BAG-03/ })).toHaveTextContent('Reusable Bag');
    expect(screen.queryByText('Add New Catalog Product')).not.toBeInTheDocument();
  });

  it('rejects adjustments below zero and applies a corrected quantity with its reason', async () => {
    const requests = serveApi({ ...catalogResponses,
      'POST /inventory/products/product-2/adjust': { data: { ...products[1], stockQuantity: 8, lowStockIndicator: false } },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    const row = await screen.findByRole('row', { name: /PAPER-02/ });
    await user.click(within(row).getByRole('button', { name: 'Adjust' }));
    const quantity = screen.getByPlaceholderText('e.g. -12 or 50');
    await user.type(quantity, '-4');
    await user.click(screen.getByRole('button', { name: 'Apply Adjustment' }));
    expect(screen.getByText('Invalid adjustment. Cannot reduce stock below 0 (current: 3, adjustment: -4).')).toBeInTheDocument();
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(0);
    await user.clear(quantity);
    await user.type(quantity, '5');
    await user.selectOptions(selectWithOption('Supplier Shortage'), 'SUPPLIER_SHORTAGE');
    await user.click(screen.getByRole('button', { name: 'Apply Adjustment' }));
    expect(await screen.findByText('Inventory adjustment successful for SKU: PAPER-02')).toBeInTheDocument();
    expect(requests.find(request => request.method === 'POST')?.body).toEqual({ quantityAdjustment: 5, reasonCode: 'SUPPLIER_SHORTAGE' });
    expect(within(screen.getByRole('row', { name: /PAPER-02/ })).getByText('8')).toBeInTheDocument();
    expect(screen.queryByText('Adjust Stock Quantity')).not.toBeInTheDocument();
  });

  it('retains the stock adjustment form when the server rejects it', async () => {
    serveApi({ ...catalogResponses,
      'POST /inventory/products/product-2/adjust': { status: 409, data: {} },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await user.click(within(await screen.findByRole('row', { name: /PAPER-02/ })).getByRole('button', { name: 'Adjust' }));
    await user.type(screen.getByPlaceholderText('e.g. -12 or 50'), '5');
    await user.click(screen.getByRole('button', { name: 'Apply Adjustment' }));
    expect(await screen.findByText('Unable to adjust inventory. Please refresh and try again.')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('e.g. -12 or 50')).toHaveValue(5);
    expect(screen.getByRole('button', { name: 'Apply Adjustment' })).toBeEnabled();
    expect(within(screen.getByRole('row', { name: /PAPER-02/ })).getByText('3')).toBeInTheDocument();
  });
});
