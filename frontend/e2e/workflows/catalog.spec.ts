import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { AuditLog, Product } from '../../src/types';
import { apiURL, bearer, getJSON, login, mutation, navigate, products, selectWithOption } from './helpers';

test('catalog creation, stock correction, filtering and audit history persist through the API', async ({ page }) => {
  const token = await login(page);
  const sku = `E2E-STOCK-${randomUUID().slice(0, 8)}`;
  await navigate(page, 'Product Catalog');
  await page.getByRole('button', { name: 'New Product' }).click();
  await page.getByPlaceholder('e.g. SKU-OFFICE-002').fill(sku);
  await page.getByPlaceholder('e.g. 12.99').fill('12.50');
  await page.getByPlaceholder('e.g. Copy Paper A4 500 Sheets').fill('Regression stock item');
  await page.getByPlaceholder('Reorder Level (Safety Margin)').fill('5');
  await selectWithOption(page, 'Select Category').selectOption({ label: 'Electronics' });
  const warehouseId = await selectWithOption(page, 'Select Warehouse').locator('option').filter({ hasText: /^Main Distribution Center/ }).getAttribute('value');
  await selectWithOption(page, 'Select Warehouse').selectOption(warehouseId!);
  const createdResponse = mutation(page, 'POST', '/inventory/products');
  await page.getByRole('button', { name: 'Create Product', exact: true }).click();
  const created = await createdResponse;
  expect(created.status()).toBe(201);
  const product = await created.json() as Product;
  expect(product).toMatchObject({ sku, stockQuantity: 0, unitPrice: 12.5, reorderLevel: 5, isActive: true });

  const row = page.getByRole('row').filter({ hasText: sku });
  await expect(row).toContainText('Regression stock item');
  await expect(row).toContainText('Electronics');
  await expect(row).toContainText('Main Distribution Center');
  await expect(row).toContainText('LOW STOCK');

  await row.getByRole('button', { name: 'Adjust', exact: true }).click();
  await page.getByPlaceholder('e.g. -12 or 50').fill('25');
  const addedResponse = mutation(page, 'POST', `/inventory/products/${product.id}/adjust`);
  await page.getByRole('button', { name: 'Apply Adjustment' }).click();
  expect((await addedResponse).status()).toBe(200);
  await expect(row.getByRole('cell').nth(4)).toHaveText('25NORMAL');

  await row.getByRole('button', { name: 'Adjust', exact: true }).click();
  await page.getByPlaceholder('e.g. -12 or 50').fill('-22');
  await selectWithOption(page, 'Damaged Goods Scrap').selectOption('DAMAGED_GOODS_SCRAP');
  const scrappedResponse = mutation(page, 'POST', `/inventory/products/${product.id}/adjust`);
  await page.getByRole('button', { name: 'Apply Adjustment' }).click();
  expect((await scrappedResponse).status()).toBe(200);
  await expect(row.getByRole('cell').nth(4)).toHaveText('3LOW STOCK');

  await row.getByRole('button', { name: 'Adjust', exact: true }).click();
  await page.getByPlaceholder('e.g. -12 or 50').fill('-4');
  await page.getByRole('button', { name: 'Apply Adjustment' }).click();
  await expect(page.getByText('Invalid adjustment. Cannot reduce stock below 0 (current: 3, adjustment: -4).')).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const rejected = await page.request.post(`${apiURL}/inventory/products/${product.id}/adjust`, {
    headers: bearer(token), data: { quantityAdjustment: -4, reasonCode: 'DAMAGED_GOODS_SCRAP' },
  });
  expect(rejected.status()).toBe(400);
  await expect.poll(async () => (await products(page, token)).find(item => item.id === product.id)?.stockQuantity).toBe(3);

  await page.getByPlaceholder('Search this page by SKU or name...').fill(sku);
  await selectWithOption(page, 'All Categories').selectOption({ label: 'Electronics' });
  await selectWithOption(page, 'All Stock').selectOption('LOW_STOCK');
  await page.getByTitle('Refresh Catalog Data').click();
  await expect(page.getByRole('row')).toHaveCount(2);
  await expect(row.getByRole('cell').nth(4)).toHaveText('3LOW STOCK');
  await page.getByPlaceholder('Search this page by SKU or name...').fill(`${sku}-absent`);
  await expect(page.getByRole('heading', { name: 'No Products Found' })).toBeVisible();

  await expect.poll(async () => (await getJSON<AuditLog[]>(page, token, '/audit-logs')).filter(log =>
    log.entityId === product.id && log.action === 'ACTION_MANUAL_ADJUSTMENT',
  ).length).toBe(2);
  await navigate(page, 'Audit Logs');
  await page.getByPlaceholder('Search IDs on this page...').fill(product.id);
  await selectWithOption(page, 'All Actions').selectOption('ACTION_MANUAL_ADJUSTMENT');
  await expect(page.getByRole('row')).toHaveCount(3);
  await page.getByRole('row').filter({ hasText: 'MANUAL ADJUSTMENT' }).first().click();
  await expect(page.getByText('Log Snapshot Details', { exact: true })).toBeVisible();
  const change = page.getByRole('row').filter({ hasText: 'stockQuantity' });
  await expect(change.getByRole('cell')).toHaveText(['stockQuantity', '25', '3']);
});

test('duplicate catalog SKUs are rejected without creating a second product', async ({ page }) => {
  const token = await login(page);
  await navigate(page, 'Product Catalog');
  const existing = (await products(page, token)).find(product => product.sku === 'SKU-ELEC-001');
  expect(existing).toBeDefined();
  await page.getByRole('button', { name: 'New Product' }).click();
  await page.getByPlaceholder('e.g. SKU-OFFICE-002').fill('SKU-ELEC-001');
  await page.getByPlaceholder('e.g. 12.99').fill('20.00');
  await page.getByPlaceholder('e.g. Copy Paper A4 500 Sheets').fill('Duplicate product attempt');
  await selectWithOption(page, 'Select Warehouse').selectOption(existing!.warehouseId!);
  const responsePromise = mutation(page, 'POST', '/inventory/products');
  await page.getByRole('button', { name: 'Create Product', exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(400);
  expect(await response.json()).toMatchObject({ message: 'SKU already exists: SKU-ELEC-001' });
  await expect(page.getByRole('dialog').getByRole('alert')).toHaveText('SKU already exists: SKU-ELEC-001');
  await expect(page.getByText('Add New Catalog Product', { exact: true })).toBeVisible();
  const matching = (await products(page, token)).filter(product => product.sku === 'SKU-ELEC-001');
  expect(matching).toHaveLength(1);
  expect(matching[0]).toEqual(existing);
});
