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
    const requests = serveApi({ ...orderResponses, 'POST /orders': () => pending.promise });
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
    pending.resolve({ data: purchaseOrder({ id: 'order-2', orderNumber: 'PO-1002', totalAmount: 37.5 }) });
    expect(await screen.findByText('Purchase Order PO-1002 created successfully as DRAFT.')).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /PO-1002/ })).toHaveTextContent('$37.50');
    expect(screen.queryByText('New Purchase Order')).not.toBeInTheDocument();
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
    ['PENDING_APPROVAL', 'Awaiting Review'],
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
