import { readFile } from 'node:fs/promises';
import { Buffer } from 'node:buffer';
import { test as base, expect, type Page } from '@playwright/test';

// These are presentation fixtures, deliberately separate from the live API tests.
// Stable records include long names, low stock, every order state, and audit diffs.
const products = [
  { id: 'product-001', sku: 'OFF-PAPER-A4', name: 'Recycled A4 Office Paper', unitPrice: 6.75, stockQuantity: 240, reorderLevel: 50, lowStockIndicator: false, categoryId: 'category-office', categoryName: 'Office Supplies', warehouseId: 'warehouse-manila', warehouseName: 'Manila Central' },
  { id: 'product-002', sku: 'SAF-GLOVE-M', name: 'Protective Work Gloves — Medium', unitPrice: 12.5, stockQuantity: 8, reorderLevel: 25, lowStockIndicator: true, categoryId: 'category-safety', categoryName: 'Safety Equipment', warehouseId: 'warehouse-cebu', warehouseName: 'Cebu Distribution' },
  { id: 'product-003', sku: 'PKG-BOX-L', name: 'Heavy Duty Shipping Carton', unitPrice: 3.2, stockQuantity: 1250, reorderLevel: 100, lowStockIndicator: false, categoryId: 'category-packaging', categoryName: 'Packaging', warehouseId: 'warehouse-manila', warehouseName: 'Manila Central' },
  { id: 'product-004', sku: 'SAF-HELMET-W', name: 'White Safety Helmet', unitPrice: 24.99, stockQuantity: 0, reorderLevel: 15, lowStockIndicator: true, categoryId: 'category-safety', categoryName: 'Safety Equipment', warehouseId: 'warehouse-cebu', warehouseName: 'Cebu Distribution' },
];

const categories = [
  { id: 'category-office', name: 'Office Supplies' },
  { id: 'category-safety', name: 'Safety Equipment' },
  { id: 'category-packaging', name: 'Packaging' },
];
const warehouses = [
  { id: 'warehouse-manila', name: 'Manila Central', location: 'Manila' },
  { id: 'warehouse-cebu', name: 'Cebu Distribution', location: 'Cebu' },
];
const suppliers = [
  { id: 'supplier-001', name: 'Pacific Trade Supplies', active: true },
  { id: 'supplier-002', name: 'Northstar Industrial', active: true },
];
const orders = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SHIPPED', 'DELIVERED', 'CANCELLED'].map((status, index) => ({
  id: `order-00${index + 1}`,
  orderNumber: `PO-2026-00${index + 1}`,
  supplierId: 'supplier-001',
  supplierName: 'Pacific Trade Supplies',
  warehouseId: 'warehouse-manila',
  warehouseName: 'Manila Central',
  status,
  totalAmount: 567.5,
  createdBy: 'ada@example.test',
  createdAt: '2026-01-14T10:30:00Z',
  items: [
    { productId: 'product-001', productName: 'Recycled A4 Office Paper', productSku: 'OFF-PAPER-A4', quantity: 10, unitPrice: 6.75, subtotal: 67.5 },
    { productId: 'product-002', productName: 'Protective Work Gloves — Medium', productSku: 'SAF-GLOVE-M', quantity: 40, unitPrice: 12.5, subtotal: 500 },
  ],
}));
const logs = [
  { id: 'audit-001', userId: 'ada@example.test', entityType: 'Product', entityId: 'product-002', action: 'ACTION_MANUAL_ADJUSTMENT', oldValue: '{"stockQuantity":12,"reorderLevel":25}', newValue: '{"stockQuantity":8,"reorderLevel":25,"reasonCode":"DAMAGED_GOODS_SCRAP"}', createdAt: '2026-01-15T11:45:00Z' },
  { id: 'audit-002', userId: 'sam@example.test', entityType: 'Order', entityId: 'order-004', action: 'ACTION_UPDATE_ORDER_STATUS', oldValue: '{"status":"APPROVED"}', newValue: '{"status":"SHIPPED"}', createdAt: '2026-01-15T10:30:00Z' },
  { id: 'audit-003', userId: 'sam@example.test', entityType: 'Order', entityId: 'order-001', action: 'ACTION_CREATE_ORDER', oldValue: '{}', newValue: '{"status":"DRAFT","totalAmount":567.5}', createdAt: '2026-01-14T09:00:00Z' },
];
const users = [
  { id: 'user-admin', fullName: 'Ada Rivera', email: 'ada@example.test', role: 'ROLE_ADMIN', status: 'ACTIVE' },
  { id: 'user-staff', fullName: 'Sam Cruz', email: 'sam@example.test', role: 'ROLE_STAFF', status: 'ACTIVE' },
  { id: 'user-staff-2', fullName: 'Alexandra Santos Reyes', email: 'alexandra@example.test', role: 'ROLE_STAFF', status: 'ACTIVE' },
];

const fixedTime = new Date('2026-01-15T12:00:00Z');
const fontPath = new URL('../../node_modules/@fontsource-variable/plus-jakarta-sans/files/plus-jakarta-sans-latin-wght-normal.woff2', import.meta.url);

type VisualState = 'loaded' | 'login-error' | 'dashboard-error';
type Fixtures = { visualState: VisualState; visualRole: 'ROLE_ADMIN' | 'ROLE_STAFF'; visualApi: void };

function paged<T>(content: T[]) {
  return { content, totalElements: content.length, totalPages: 1, pageNumber: 0, pageSize: 50, hasNext: false };
}

export const test = base.extend<Fixtures>({
  visualState: ['loaded', { option: true }],
  visualRole: ['ROLE_ADMIN', { option: true }],
  visualApi: [async ({ page, visualState, visualRole }, use) => {
    const unexpectedRequests: string[] = [];
    const pageErrors: string[] = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.clock.setFixedTime(fixedTime);

    // Serve the real typeface from the pinned npm package: no internet or font races.
    await page.route('https://fonts.googleapis.com/**', route => route.fulfill({
      contentType: 'text/css',
      body: '@font-face { font-family: "Plus Jakarta Sans"; font-style: normal; font-weight: 200 800; font-display: block; src: url(https://fonts.gstatic.com/visual/plus-jakarta-sans.woff2) format("woff2"); }',
    }));
    await page.route('https://fonts.gstatic.com/**', async route => route.fulfill({
      contentType: 'font/woff2',
      headers: { 'access-control-allow-origin': '*' },
      body: await readFile(fontPath),
    }));

    await page.route('**/api/v1/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname.replace('/api/v1', '');
      const key = `${request.method()} ${path}`;
      if (key === 'POST /auth/login') {
        if (visualState === 'login-error') {
          await route.fulfill({ status: 401, json: { message: 'Unable to sign in. Check your email and password.' } });
          return;
        }
        const user = visualRole === 'ROLE_ADMIN' ? users[0] : users[1];
        const payload = Buffer.from(JSON.stringify({
          userId: user.id, sub: user.email, fullName: user.fullName, role: user.role,
          exp: Math.floor(fixedTime.getTime() / 1000) + 3600,
        })).toString('base64url');
        // Unsigned, synthetic token is accepted only by this intercepted visual API.
        await route.fulfill({ json: { token: `visual.${payload}.fixture` } });
        return;
      }
      if (key === 'GET /analytics/dashboard' && visualState === 'dashboard-error') {
        await route.fulfill({ status: 503, json: { message: 'Metrics unavailable' } });
        return;
      }
      const responses: Record<string, unknown> = {
        'GET /analytics/dashboard': {
          totalRevenue: 128450.75, totalInventoryValue: 245630.5, lowStockCount: 2,
          orderStatusCounts: { DRAFT: 3, PENDING_APPROVAL: 5, APPROVED: 4, SHIPPED: 6, DELIVERED: 21, CANCELLED: 2 },
          warehouseStockCounts: { 'Manila Central': 4250, 'Cebu Distribution': 1875, 'Davao Hub': 940 },
          topProducts: [
            { name: 'Recycled A4 Office Paper', totalQuantityOrdered: 850 },
            { name: 'Heavy Duty Shipping Carton', totalQuantityOrdered: 620 },
            { name: 'Protective Work Gloves — Medium', totalQuantityOrdered: 410 },
          ],
        },
        'GET /inventory/products': paged(products),
        'GET /categories': categories,
        'GET /warehouses': warehouses,
        'GET /suppliers': suppliers,
        'GET /orders': paged(orders),
        'GET /audit-logs': logs,
        'GET /users': users,
        'GET /notifications': {
          notifications: [
            { id: 'notification-001', userId: 'user-admin', type: 'LOW_STOCK', message: 'Protective Work Gloves — Medium is below the reorder level.', isRead: false, createdAt: '2026-01-15T11:45:00Z' },
            { id: 'notification-002', userId: 'user-admin', type: 'ORDER_STATUS', message: 'Purchase order PO-2026-004 has shipped.', isRead: false, createdAt: '2026-01-15T10:30:00Z' },
            { id: 'notification-003', userId: 'user-admin', type: 'ORDER_STATUS', message: 'Purchase order PO-2026-005 was delivered.', isRead: true, createdAt: '2026-01-14T14:00:00Z' },
          ],
          totalCount: 3, unreadCount: 2,
        },
      };
      if (Object.hasOwn(responses, key)) {
        await route.fulfill({ json: responses[key] });
        return;
      }
      unexpectedRequests.push(key);
      await route.fulfill({ status: 501, json: { message: `Missing visual fixture: ${key}` } });
    });
    await use();
    expect(unexpectedRequests, 'Every visual API request must have an explicit fixture').toEqual([]);
    expect(pageErrors, 'Visual screens must render without uncaught browser errors').toEqual([]);
  }, { auto: true }],
});

export { expect };

export async function signIn(page: Page, role: 'admin' | 'staff' = 'admin') {
  await page.goto('/login');
  await page.getByLabel('Email Address').fill(role === 'admin' ? 'ada@example.test' : 'sam@example.test');
  await page.getByLabel('Password').fill('visual fixture');
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page.getByRole('heading', { name: role === 'admin' ? 'Operations Dashboard' : 'Product Catalog', exact: true })).toBeVisible();
}

export async function navigate(page: Page, label: string, readyText: string) {
  await page.getByRole('link', { name: label, exact: true }).click();
  await expect(page.getByText(readyText, { exact: true }).first()).toBeVisible();
}

export async function capture(page: Page, name: string) {
  const fontLoaded = await page.evaluate(async () => {
    await document.fonts.ready;
    return [...document.fonts].some(font => font.family.includes('Plus Jakarta Sans') && font.status === 'loaded');
  });
  expect(fontLoaded, 'Snapshots must use the bundled typeface, never a fallback font').toBe(true);
  await page.mouse.move(0, 0);
  await expect(page).toHaveScreenshot(name, { fullPage: true, animations: 'disabled', caret: 'hide' });
}
