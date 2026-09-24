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

  it('keeps the page number and rows together when a page request fails', async () => {
    let fail = true;
    const requests = serveApi({ ...catalogResponses,
      'GET /inventory/products?page=0&size=50': { data: pageOf([products[0]], 0, true) },
      'GET /inventory/products?page=1&size=50': () => fail ? { status: 503, data: {} }
        : { data: pageOf([products[1]], 1) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Unable to load products. Please try again.');
    expect(screen.getByText('Showing page', { exact: false })).toHaveTextContent('Showing page 1');
    expect(screen.getByText('Hand Soap')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Paper Towels');
    expect(requests.filter(request => request.url.startsWith('/inventory/products')).map(request => request.url))
      .toEqual(['/inventory/products?page=0&size=50', '/inventory/products?page=1&size=50', '/inventory/products?page=1&size=50']);
  });

  it('accepts a zero price and renders a server rejection inside the active product dialog', async () => {
    const requests = serveApi({ ...catalogResponses,
      'POST /inventory/products': { status: 409, data: { message: 'SKU already exists' } },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'New Product' }));
    await user.type(screen.getByPlaceholderText('e.g. SKU-OFFICE-002'), 'SOAP-01');
    await user.type(screen.getByPlaceholderText('e.g. Copy Paper A4 500 Sheets'), 'Sample');
    await user.type(screen.getByPlaceholderText('e.g. 12.99'), '0');
    await user.selectOptions(selectWithOption('Select Warehouse'), 'warehouse-1');
    await user.click(screen.getByRole('button', { name: 'Create Product' }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('SKU already exists');
    expect(requests.find(request => request.method === 'POST')?.body).toMatchObject({ unitPrice: 0 });
  });

  it('requires a warehouse for new products and distinguishes duplicate warehouse names by location', async () => {
    const requests = serveApi({ ...catalogResponses,
      'GET /warehouses': { data: [{ id: 'east', name: 'Depot', location: 'East' }, { id: 'west', name: 'Depot', location: 'West' }] },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'New Product' }));
    expect(selectWithOption('Select Warehouse')).toBeRequired();
    expect(screen.getByRole('option', { name: 'Depot — East' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Depot — West' })).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText('e.g. SKU-OFFICE-002'), 'SAMPLE');
    await user.type(screen.getByPlaceholderText('e.g. Copy Paper A4 500 Sheets'), 'Sample');
    await user.type(screen.getByPlaceholderText('e.g. 12.99'), '1');
    await user.click(screen.getByRole('button', { name: 'Create Product' }));
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(0);
  });

  it.each([false, true])('reloads the authoritative page after creation and keeps creation success when reload fails: %s', async (reloadFails) => {
    let created = false;
    const requests = serveApi({ ...catalogResponses,
      'GET /inventory/products?page=0&size=50': { data: pageOf([products[0]], 0, true) },
      'GET /inventory/products?page=1&size=50': () => created && reloadFails
        ? { status: 503, data: {} } : { data: pageOf([products[1]], 1, created) },
      'POST /inventory/products': () => { created = true; return { data: { ...products[0], id: 'new-id', sku: 'NEW-SKU' } }; },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Paper Towels');
    await user.click(screen.getByRole('button', { name: 'New Product' }));
    await user.type(screen.getByPlaceholderText('e.g. SKU-OFFICE-002'), 'NEW-SKU');
    await user.type(screen.getByPlaceholderText('e.g. Copy Paper A4 500 Sheets'), 'New Product');
    await user.type(screen.getByPlaceholderText('e.g. 12.99'), '1');
    await user.selectOptions(selectWithOption('Select Warehouse'), 'warehouse-1');
    await user.click(screen.getByRole('button', { name: 'Create Product' }));
    await screen.findByText('Successfully cataloged product: NEW-SKU');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await screen.findByText('Paper Towels')).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /NEW-SKU/ })).not.toBeInTheDocument();
    expect(screen.getByText('Showing page', { exact: false })).toHaveTextContent('Showing page 2');
    if (reloadFails) expect(await screen.findByText('Unable to load products. Please try again.')).toBeInTheDocument();
    else expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(1);
    expect(requests.filter(request => request.url === '/inventory/products?page=1&size=50')).toHaveLength(2);
  });

  it('creates a product and prevents another submission while the request is pending', async () => {
    const pending = deferredReply();
    let rows = products;
    const requests = serveApi({ ...catalogResponses,
      'GET /inventory/products?page=0&size=50': () => ({ data: pageOf(rows) }),
      'POST /inventory/products': () => pending.promise,
    });
    const user = userEvent.setup();
    renderAuthenticated(<Catalog />);
    await screen.findByText('Hand Soap');
    await user.click(screen.getByRole('button', { name: 'New Product' }));
    await user.type(screen.getByPlaceholderText('e.g. SKU-OFFICE-002'), 'BAG-03');
    await user.type(screen.getByPlaceholderText('e.g. Copy Paper A4 500 Sheets'), 'Reusable Bag');
    await user.type(screen.getByPlaceholderText('e.g. 12.99'), '4.50');
    await user.selectOptions(selectWithOption('Select Category'), 'home');
    await user.selectOptions(selectWithOption('Select Warehouse'), 'warehouse-1');
    await user.click(screen.getByRole('button', { name: 'Create Product' }));
    expect(screen.getByRole('button', { name: 'Creating...' })).toBeDisabled();
    expect(requests.find(request => request.method === 'POST')?.body).toEqual({
      sku: 'BAG-03', name: 'Reusable Bag', unitPrice: 4.5, reorderLevel: 10,
      categoryId: 'home', warehouseId: 'warehouse-1',
    });
    const created = { ...products[0], id: 'product-3', sku: 'BAG-03', name: 'Reusable Bag', unitPrice: 4.5 };
    rows = [...products, created];
    pending.resolve({ data: created });
    expect(await screen.findByText('Successfully cataloged product: BAG-03')).toBeInTheDocument();
    expect(await screen.findByRole('row', { name: /BAG-03/ })).toHaveTextContent('Reusable Bag');
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
    expect(within(screen.getByRole('dialog')).getByRole('alert')).toHaveTextContent('Invalid adjustment. Cannot reduce stock below 0 (current: 3, adjustment: -4).');
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
