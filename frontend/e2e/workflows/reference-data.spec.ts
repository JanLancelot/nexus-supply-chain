import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { Product } from '../../src/types';
import type { Category, Warehouse } from '../../src/services/products';
import type { Supplier } from '../../src/services/suppliers';
import { getJSON, login, mutation, navigate, selectWithOption } from './helpers';

test('administrator provisions reference data and supplier sourcing through the UI', async ({ page }) => {
  const token = await login(page);
  const suffix = randomUUID().slice(0, 8);
  await navigate(page, 'Reference Data');
  await page.getByLabel('Category name', { exact: true }).fill(`Setup category ${suffix}`);
  const categoryResponse = mutation(page, 'POST', '/categories');
  await page.getByRole('button', { name: 'Create category', exact: true }).click();
  const category = await (await categoryResponse).json() as Category;
  await expect(page.getByRole('status')).toHaveText(`Category ${category.name} created.`);

  await page.getByLabel('Warehouse name', { exact: true }).fill(`Setup warehouse ${suffix}`);
  await page.getByLabel('Location', { exact: true }).fill('Test location');
  const warehouseResponse = mutation(page, 'POST', '/warehouses');
  await page.getByRole('button', { name: 'Create warehouse', exact: true }).click();
  const warehouse = await (await warehouseResponse).json() as Warehouse;
  await expect(page.getByRole('status')).toHaveText(`Warehouse ${warehouse.name} created.`);

  await page.getByLabel('Supplier name', { exact: true }).fill(`Setup supplier ${suffix}`);
  await page.getByLabel('Email', { exact: true }).fill(`setup-${suffix}@example.test`);
  const supplierResponse = mutation(page, 'POST', '/suppliers');
  await page.getByRole('button', { name: 'Create supplier', exact: true }).click();
  const supplier = await (await supplierResponse).json() as Supplier;
  await expect(page.getByRole('status')).toHaveText(`Supplier ${supplier.name} created.`);

  await navigate(page, 'Product Catalog');
  await page.getByRole('button', { name: 'New Product' }).click();
  await page.getByPlaceholder('e.g. SKU-OFFICE-002').fill(`SETUP-${suffix}`);
  await page.getByPlaceholder('e.g. 12.99').fill('0');
  await page.getByPlaceholder('e.g. Copy Paper A4 500 Sheets').fill(`Setup sample ${suffix}`);
  await selectWithOption(page, 'Select Category').selectOption(category.id);
  await selectWithOption(page, 'Select Warehouse').selectOption(warehouse.id);
  const productResponse = mutation(page, 'POST', '/inventory/products');
  await page.getByRole('button', { name: 'Create Product', exact: true }).click();
  expect((await productResponse).status()).toBe(201);
  const product = await (await productResponse).json() as Product;
  expect(product.unitPrice).toBe(0);

  await navigate(page, 'Reference Data');
  await page.getByRole('button', { name: `Manage products for ${supplier.name}`, exact: true }).click();
  const form = page.getByRole('form', { name: 'Supplier product sourcing', exact: true });
  await form.getByRole('textbox', { name: 'Search products by SKU or name' }).fill(product.sku);
  await form.getByRole('button', { name: 'Search', exact: true }).click();
  await form.getByRole('combobox').selectOption(product.id);
  const sourceResponse = mutation(page, 'PUT', `/suppliers/${supplier.id}/products`);
  await form.getByRole('button', { name: 'Save supplier products', exact: true }).click();
  expect((await sourceResponse).ok()).toBe(true);
  expect((await getJSON<Supplier[]>(page, token, '/suppliers')).find(row => row.id === supplier.id)?.productIds).toEqual([product.id]);

  await navigate(page, 'Product Catalog');
  await navigate(page, 'Reference Data');
  await expect(page.getByRole('heading', { name: 'Reference Data', exact: true })).toBeVisible();
  await expect(page.getByText(supplier.name, { exact: true })).toBeVisible();
});
