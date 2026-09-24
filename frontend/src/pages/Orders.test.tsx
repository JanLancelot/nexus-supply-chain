import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { orderResponses, pageOf, purchaseOrder } from '../test/fixtures';
import { deferredReply, serveApi } from '../test/http';
import { renderAuthenticated, selectWithOption } from '../test/render';
import Orders from './Orders';

async function openWizard(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText('PO-1001');
  await user.click(screen.getByRole('button', { name: 'Create Purchase Order' }));
  await user.selectOptions(selectWithOption('Choose Supplier'), 'supplier-1');
  await user.selectOptions(selectWithOption('Choose Warehouse'), 'warehouse-1');
  await user.selectOptions(selectWithOption('Select SKU Product'), 'product-1');
}

describe('purchase orders', () => {
  it('calculates a draft total, excludes inactive suppliers and submits the chosen items', async () => {
    const pending = deferredReply();
    let rows = [purchaseOrder()];
    const requests = serveApi({ ...orderResponses,
      'GET /orders?page=0&size=50': () => ({ data: pageOf(rows) }),
      'POST /orders': () => pending.promise });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await openWizard(user);
    expect(screen.queryByRole('option', { name: 'Retired Supplier' })).not.toBeInTheDocument();
    const quantity = screen.getByRole('spinbutton');
    await user.clear(quantity);
    await user.type(quantity, '3');
    expect(screen.getAllByText('$37.50')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Save Draft Order' }));
    expect(screen.getByRole('button', { name: 'Saving...' })).toBeDisabled();
    expect(requests.find(request => request.method === 'POST')?.body).toEqual({
      supplierId: 'supplier-1', warehouseId: 'warehouse-1', items: [{ productId: 'product-1', quantity: 3 }],
    });
    const created = purchaseOrder({ id: 'order-2', orderNumber: 'PO-1002', totalAmount: 37.5 });
    rows = [created, ...rows];
    pending.resolve({ data: created });
    expect(await screen.findByText('Purchase Order PO-1002 created successfully as DRAFT.')).toBeInTheDocument();
    expect(await screen.findByRole('row', { name: /PO-1002/ })).toHaveTextContent('$37.50');
    expect(screen.queryByText('New Purchase Order')).not.toBeInTheDocument();
  });

  it.each([false, true])('returns to server page zero after create and separates refresh failure from success: %s', async (reloadFails) => {
    let created = false;
    const newOrder = purchaseOrder({ id: 'new-order', orderNumber: 'PO-NEW' });
    const requests = serveApi({ ...orderResponses,
      'GET /orders?page=0&size=50': () => created && reloadFails ? { status: 503, data: {} }
        : { data: pageOf(created ? [newOrder] : [purchaseOrder()], 0, !created) },
      'GET /orders?page=1&size=50': { data: pageOf([purchaseOrder({ id: 'old-order', orderNumber: 'PO-OLD' })], 1) },
      'POST /orders': () => { created = true; return { data: newOrder }; },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await screen.findByText('PO-1001');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('PO-OLD');
    await user.click(screen.getByRole('button', { name: 'Create Purchase Order' }));
    await user.selectOptions(selectWithOption('Choose Supplier'), 'supplier-1');
    await user.selectOptions(selectWithOption('Choose Warehouse'), 'warehouse-1');
    await user.selectOptions(selectWithOption('Select SKU Product'), 'product-1');
    await user.click(screen.getByRole('button', { name: 'Save Draft Order' }));
    await screen.findByText('Purchase Order PO-NEW created successfully as DRAFT.');
    expect(screen.queryByText('New Purchase Order')).not.toBeInTheDocument();
    if (reloadFails) {
      expect(await screen.findByText('Unable to load purchase orders. Please try again.')).toBeInTheDocument();
      expect(screen.getByRole('row', { name: /PO-OLD/ })).toBeInTheDocument();
      expect(screen.queryByRole('row', { name: /PO-NEW/ })).not.toBeInTheDocument();
      expect(screen.getByText('Showing page', { exact: false })).toHaveTextContent('Showing page 2');
    } else {
      expect(await screen.findByRole('row', { name: /PO-NEW/ })).toBeInTheDocument();
      expect(screen.queryByRole('row', { name: /PO-OLD/ })).not.toBeInTheDocument();
      expect(screen.getByText('Showing page', { exact: false })).toHaveTextContent('Showing page 1');
    }
    expect(requests.filter(request => request.url === '/orders?page=0&size=50')).toHaveLength(2);
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(1);
  });

  it('shows item identity from the order response without loading catalog page zero', async () => {
    const requests = serveApi({ ...orderResponses,
      'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder({ items: [{
        productId: 'product-91', productName: 'Remote Catalog Item', productSku: 'SKU-91', quantity: 2, unitPrice: 12.5, subtotal: 25,
      }] })]) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    expect(screen.getByText('Remote Catalog Item')).toBeInTheDocument();
    expect(screen.getByText('SKU-91')).toBeInTheDocument();
    expect(requests.some(request => request.url.startsWith('/inventory/products'))).toBe(false);
  });

  it('shows the API rejection and retains the draft fields', async () => {
    serveApi({ ...orderResponses, 'POST /orders': { status: 400, data: { message: 'Product was deactivated. Choose another product.' } } });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await openWizard(user);
    await user.click(screen.getByRole('button', { name: 'Save Draft Order' }));
    expect(await screen.findByText('Product was deactivated. Choose another product.')).toBeInTheDocument();
    expect(selectWithOption('Select SKU Product')).toHaveValue('product-1');
  });

  it('rejects duplicate products and allows the extra line to be removed', async () => {
    const requests = serveApi(orderResponses);
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await openWizard(user);
    expect(screen.getByRole('button', { name: 'Remove Line Item' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Add Line' }));
    const productSelects = screen.getAllByRole('combobox').filter(element =>
      within(element).queryByRole('option', { name: 'Select SKU Product' }),
    );
    await user.selectOptions(productSelects[1], 'product-1');
    await user.click(screen.getByRole('button', { name: 'Save Draft Order' }));
    expect(screen.getByText('Select each product only once per order.')).toBeInTheDocument();
    expect(requests.filter(request => request.method === 'POST')).toHaveLength(0);
    await user.click(screen.getAllByRole('button', { name: 'Remove Line Item' })[1]);
    expect(screen.getByRole('button', { name: 'Remove Line Item' })).toBeDisabled();
    expect(screen.getAllByRole('spinbutton')).toHaveLength(1);
  });

  it.each([
    ['APPROVED', 'Order Approved'],
    ['SHIPPED', 'In Transit'],
  ] as const)('shows staff the %s progress without administrator actions', async (status, message) => {
    serveApi({ ...orderResponses, 'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder({ status })]) } });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />, 'ROLE_STAFF');
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    expect(screen.getByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Approve Order|Dispatch \/ Ship|Confirm Delivery|Cancel Order|Reject & Cancel/ })).not.toBeInTheDocument();
  });

  it('lets staff cancel an order awaiting approval', async () => {
    const requests = serveApi({ ...orderResponses,
      'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder({ status: 'PENDING_APPROVAL' })]) },
      'PUT /orders/order-1/status': { data: purchaseOrder({ status: 'CANCELLED' }) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />, 'ROLE_STAFF');
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    await user.click(screen.getByRole('button', { name: 'Cancel Order' }));
    expect(await screen.findByText('This purchase order has been cancelled.')).toBeInTheDocument();
    expect(requests.find(request => request.method === 'PUT')?.body).toEqual({ status: 'CANCELLED' });
  });

  it('keeps the page number and rows together after a failed next page and retries that page', async () => {
    let fail = true;
    const requests = serveApi({ ...orderResponses,
      'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder()], 0, true) },
      'GET /orders?page=1&size=50': () => fail ? { status: 503, data: {} }
        : { data: pageOf([purchaseOrder({ id: 'order-2', orderNumber: 'PO-1002' })], 1) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await screen.findByText('PO-1001');
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Unable to load purchase orders. Please try again.');
    expect(screen.getByText('Showing page', { exact: false })).toHaveTextContent('Showing page 1');
    expect(screen.getByText('PO-1001')).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('PO-1002');
    expect(requests.filter(request => request.url.startsWith('/orders?')).map(request => request.url))
      .toEqual(['/orders?page=0&size=50', '/orders?page=1&size=50', '/orders?page=1&size=50']);
  });

  it('keeps a failed transition retryable, then updates the order detail and list from the response', async () => {
    let conflict = true;
    const requests = serveApi({ ...orderResponses,
      'PUT /orders/order-1/status': () => conflict
        ? { status: 409, data: { message: 'Order was updated. Please retry.' } }
        : { data: purchaseOrder({ status: 'PENDING_APPROVAL' }) },
    });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    await user.click(screen.getByRole('button', { name: 'Submit for Approval' }));
    expect(await screen.findByText('Order was updated. Please retry.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit for Approval' })).toBeEnabled();
    conflict = false;
    await user.click(screen.getByRole('button', { name: 'Submit for Approval' }));
    expect(await screen.findByRole('button', { name: 'Approve Order' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit for Approval' })).not.toBeInTheDocument();
    expect(requests.filter(request => request.method === 'PUT').map(request => request.body))
      .toEqual([{ status: 'PENDING_APPROVAL' }, { status: 'PENDING_APPROVAL' }]);
    await user.click(screen.getByRole('button', { name: 'Back to List' }));
    expect(screen.getByRole('row', { name: /PO-1001/ })).toHaveTextContent('PENDING APPROVAL');
  });

  it.each(['DELIVERED', 'CANCELLED'] as const)('locks status changes on a %s order', async (status) => {
    serveApi({ ...orderResponses, 'GET /orders?page=0&size=50': { data: pageOf([purchaseOrder({ status })]) } });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await user.click(await screen.findByRole('button', { name: 'Inspect' }));
    expect(screen.getByText('Status changes are locked.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Approve Order|Submit for Approval|Cancel Order|Confirm Delivery/ })).not.toBeInTheDocument();
  });

  it('combines status and supplier search, showing an empty state when no order matches', async () => {
    serveApi({ ...orderResponses, 'GET /orders?page=0&size=50': { data: pageOf([
      purchaseOrder(), purchaseOrder({ id: 'order-2', orderNumber: 'PO-1002', status: 'DELIVERED', supplierName: 'South Supply' }),
    ]) } });
    const user = userEvent.setup();
    renderAuthenticated(<Orders />);
    await screen.findByText('PO-1002');
    await user.selectOptions(selectWithOption('All Statuses'), 'DELIVERED');
    expect(screen.queryByText('PO-1001')).not.toBeInTheDocument();
    await user.type(screen.getByPlaceholderText('Search orders on this page...'), 'north');
    expect(screen.getByText('No Purchase Orders Found')).toBeInTheDocument();
    await user.clear(screen.getByPlaceholderText('Search orders on this page...'));
    expect(screen.getByRole('row', { name: /PO-1002/ })).toHaveTextContent('South Supply');
  });
});
