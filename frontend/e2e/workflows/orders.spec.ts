import { expect, test, type Page } from '@playwright/test';
import type { DashboardMetrics, Notification, Order, Product } from '../../src/types';
import { apiURL, bearer, getJSON, login, mutation, navigate, products, selectWithOption } from './helpers';

interface Notifications {
  notifications: Notification[];
  unreadCount: number;
  totalCount: number;
}

async function startOrder(page: Page, product: Product, quantity: number) {
  await navigate(page, 'Purchase Orders');
  await expect(page.getByText('Loading purchase orders...', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Create Purchase Order' }).click();
  await selectWithOption(page, 'Choose Supplier').selectOption({ label: 'Apex Logistics & Supplies' });
  await selectWithOption(page, 'Choose Warehouse').selectOption(product.warehouseId!);
  await selectWithOption(page, 'Select SKU Product').selectOption(product.id);
  await page.getByPlaceholder('Qty', { exact: true }).fill(String(quantity));
}

async function saveOrder(page: Page): Promise<Order> {
  const responsePromise = mutation(page, 'POST', '/orders');
  await page.getByRole('button', { name: 'Save Draft Order' }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  const order = await response.json() as Order;
  await expect(page.getByRole('row').filter({ hasText: order.orderNumber })).toContainText('DRAFT');
  return order;
}

async function inspectOrder(page: Page, order: Order) {
  await page.getByPlaceholder('Search orders on this page...').fill(order.orderNumber);
  await page.getByRole('row').filter({ hasText: order.orderNumber }).getByRole('button', { name: 'Inspect' }).click();
  await expect(page.getByRole('heading', { name: 'Order Details' })).toBeVisible();
}

async function transition(page: Page, order: Order, button: string, status: Order['status']) {
  const responsePromise = mutation(page, 'PUT', `/orders/${order.id}/status`);
  await page.getByRole('button', { name: button, exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ id: order.id, status });
  await expect(page.getByText(`Order ${order.orderNumber} successfully advanced to ${status}`, { exact: true })).toBeVisible();
}

test('staff submission, admin approval and delivery update inventory once and notify reviewers', async ({ page }) => {
  const staffToken = await login(page, 'staff');
  const catalog = await products(page, staffToken);
  const router = catalog.find(product => product.sku === 'SKU-ELEC-001')!;
  const valve = catalog.find(product => product.sku === 'SKU-IND-999')!;
  expect(router).toBeDefined();
  expect(valve).toBeDefined();
  await startOrder(page, router, 3);
  await page.getByRole('button', { name: 'Add Line' }).click();
  await selectWithOption(page, 'Select SKU Product').nth(1).selectOption(valve.id);
  await page.getByPlaceholder('Qty', { exact: true }).nth(1).fill('2');
  const expectedTotal = Math.round((router.unitPrice * 3 + valve.unitPrice * 2) * 100) / 100;
  await expect(page.getByText(`$${expectedTotal.toFixed(2)}`, { exact: true })).toBeVisible();
  const order = await saveOrder(page);
  expect(order.totalAmount).toBe(expectedTotal);
  expect(order.items).toEqual(expect.arrayContaining([
    expect.objectContaining({ productId: router.id, quantity: 3, unitPrice: router.unitPrice }),
    expect.objectContaining({ productId: valve.id, quantity: 2, unitPrice: valve.unitPrice }),
  ]));
  await inspectOrder(page, order);
  await transition(page, order, 'Submit for Approval', 'PENDING_APPROVAL');
  await expect(page.getByText('Awaiting Review', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve Order' })).toHaveCount(0);

  const denied = await page.request.put(`${apiURL}/orders/${order.id}/status`, {
    headers: bearer(staffToken), data: { status: 'APPROVED' },
  });
  expect(denied.status()).toBe(403);
  expect(await getJSON<Order>(page, staffToken, `/orders/${order.id}`)).toMatchObject({ status: 'PENDING_APPROVAL' });

  await page.getByRole('button', { name: 'Sign Out' }).click();
  const adminToken = await login(page);
  const metricsBeforeDelivery = await getJSON<DashboardMetrics>(page, adminToken, '/analytics/dashboard');
  const pendingMessage = `Purchase Order ${order.orderNumber} is pending administrative approval`;
  await expect.poll(async () => (await getJSON<Notifications>(page, adminToken, '/notifications'))
    .notifications.some(notification => notification.message === pendingMessage)).toBe(true);
  await page.getByRole('button', { name: 'Notifications', exact: true }).click();
  await expect(page.getByText(pendingMessage, { exact: true }).first()).toBeVisible({ timeout: 15_000 });
  const readAllResponse = mutation(page, 'PUT', '/notifications/read-all');
  await page.getByRole('button', { name: 'Read All', exact: true }).click();
  expect((await readAllResponse).status()).toBe(200);
  await expect.poll(async () => (await getJSON<Notifications>(page, adminToken, '/notifications')).unreadCount).toBe(0);
  await expect(page.getByRole('button', { name: 'Read All', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Notifications', exact: true }).click();

  await navigate(page, 'Purchase Orders');
  await inspectOrder(page, order);
  await transition(page, order, 'Approve Order', 'APPROVED');
  await transition(page, order, 'Dispatch / Ship', 'SHIPPED');
  await transition(page, order, 'Confirm Delivery', 'DELIVERED');
  await expect(page.getByText('Order Closed', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Confirm Delivery' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Cancel Order' })).toHaveCount(0);

  for (const [product, quantity] of [[router, 3], [valve, 2]] as const) {
    await expect.poll(async () => (await products(page, adminToken)).find(item => item.id === product.id)?.stockQuantity)
      .toBe(product.stockQuantity + quantity);
  }
  // Retried delivery requests must not receive the shipment a second time.
  const retry = await page.request.put(`${apiURL}/orders/${order.id}/status`, {
    headers: bearer(adminToken), data: { status: 'DELIVERED' },
  });
  expect(retry.status()).toBe(200);
  const afterRetry = await products(page, adminToken);
  expect(afterRetry.find(product => product.id === router.id)?.stockQuantity).toBe(router.stockQuantity + 3);
  expect(afterRetry.find(product => product.id === valve.id)?.stockQuantity).toBe(valve.stockQuantity + 2);
  const closed = await page.request.put(`${apiURL}/orders/${order.id}/status`, {
    headers: bearer(adminToken), data: { status: 'CANCELLED' },
  });
  expect(closed.status()).toBe(400);
  await navigate(page, 'Product Catalog');
  await expect(page.getByRole('row').filter({ hasText: router.sku }).getByRole('cell').nth(4))
    .toHaveText(`${router.stockQuantity + 3}NORMAL`);

  const metrics = await getJSON<DashboardMetrics>(page, adminToken, '/analytics/dashboard');
  expect(metrics.totalRevenue).toBeCloseTo(metricsBeforeDelivery.totalRevenue + expectedTotal, 2);
  expect(metrics.totalInventoryValue).toBeCloseTo(metricsBeforeDelivery.totalInventoryValue + expectedTotal, 2);
  expect(metrics.orderStatusCounts.DELIVERED).toBe((metricsBeforeDelivery.orderStatusCounts.DELIVERED ?? 0) + 1);
  await navigate(page, 'Dashboard', 'Operations Dashboard');
  const deliveredValue = page.getByText('Delivered Order Value', { exact: true }).locator('..');
  await expect(deliveredValue).toContainText(`$${metrics.totalRevenue.toLocaleString('en-US', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  })}`);
  await expect(page.getByRole('heading', { name: 'Most Ordered Products' })).toBeVisible();
});

test('duplicate line validation is recoverable and cancelling a draft leaves stock unchanged', async ({ page }) => {
  const token = await login(page, 'staff');
  const product = (await products(page, token)).find(item => item.sku === 'SKU-ELEC-002')!;
  expect(product).toBeDefined();
  await startOrder(page, product, 2);
  await page.getByRole('button', { name: 'Add Line' }).click();
  await selectWithOption(page, 'Select SKU Product').nth(1).selectOption(product.id);
  await page.getByRole('button', { name: 'Save Draft Order' }).click();
  await expect(page.getByText('Select each product only once per order.', { exact: true })).toBeVisible();
  await page.getByTitle('Remove Line Item').nth(1).click();
  const order = await saveOrder(page);
  expect(order.items).toHaveLength(1);
  await inspectOrder(page, order);
  await transition(page, order, 'Cancel Order', 'CANCELLED');
  await expect(page.getByText('This purchase order has been cancelled.')).toBeVisible();
  await expect(page.getByText('Order Closed', { exact: true })).toBeVisible();
  expect((await products(page, token)).find(item => item.id === product.id)?.stockQuantity).toBe(product.stockQuantity);
  expect(await getJSON<Order>(page, token, `/orders/${order.id}`)).toMatchObject({ status: 'CANCELLED' });
  await page.getByRole('button', { name: 'Back to List', exact: true }).click();
  await selectWithOption(page, 'All Statuses').selectOption('CANCELLED');
  await expect(page.getByRole('row').filter({ hasText: order.orderNumber })).toContainText('CANCELLED');
  await selectWithOption(page, 'All Statuses').selectOption('DRAFT');
  await expect(page.getByRole('heading', { name: 'No Purchase Orders Found' })).toBeVisible();
});


test('staff can cancel an order pending approval without changing inventory', async ({ page }) => {
  const token = await login(page, 'staff');
  const product = (await products(page, token)).find(item => item.sku === 'SKU-ELEC-002')!;
  await startOrder(page, product, 1);
  const order = await saveOrder(page);
  await inspectOrder(page, order);
  await transition(page, order, 'Submit for Approval', 'PENDING_APPROVAL');
  await expect(page.getByRole('button', { name: 'Approve Order' })).toHaveCount(0);
  await transition(page, order, 'Cancel Order', 'CANCELLED');
  expect(await getJSON<Order>(page, token, `/orders/${order.id}`)).toMatchObject({ status: 'CANCELLED' });
  expect((await products(page, token)).find(item => item.id === product.id)?.stockQuantity).toBe(product.stockQuantity);
});
