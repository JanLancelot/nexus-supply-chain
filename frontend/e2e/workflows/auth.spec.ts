import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { apiURL, bearer, login, navigate, products } from './helpers';

test('protected pages require login and a rejected login can recover', async ({ page }) => {
  await page.goto('/orders');
  await expect(page).toHaveURL(/\/login$/);
  const anonymous = await page.request.get(`${apiURL}/inventory/products`);
  expect(anonymous.status()).toBe(401);

  await page.getByLabel('Email Address').fill('admin@pg.com');
  await page.getByLabel('Password', { exact: true }).fill(randomUUID());
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page.getByText('Invalid email or password', { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);

  await login(page);
  await expect(page.getByRole('heading', { name: 'Operations Dashboard' })).toBeVisible();
  await navigate(page, 'Product Catalog');
  await page.getByRole('button', { name: 'Sign Out' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goBack();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('link', { name: 'Product Catalog' })).toHaveCount(0);
});

test('sessions are memory-only and stale credentials are removed', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('token', 'expired-session-from-an-older-client'));
  await login(page, 'staff');
  expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull();
  await page.reload();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeVisible();
});

test('an authenticated browser returns to login when its real token expires', async ({ page }) => {
  await page.clock.install();
  const token = await login(page, 'staff');
  const { exp } = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString()) as { exp: number };
  const browserTime = await page.evaluate(() => Date.now());
  await page.clock.fastForward(exp * 1000 - browserTime + 1_000);
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeVisible();
});

test('staff navigation and API enforce administrative permissions', async ({ page }) => {
  const token = await login(page, 'staff');
  for (const name of ['Dashboard', 'User Management', 'Audit Logs']) {
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(0);
  }
  await expect(page.getByRole('button', { name: 'New Product' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Adjust', exact: true })).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: 'SKU-ELEC-001' })).toBeVisible();

  for (const path of ['/users', '/audit-logs', '/analytics/dashboard']) {
    const response = await page.request.get(`${apiURL}${path}`, { headers: bearer(token) });
    expect(response.status()).toBe(403);
  }
  const response = await page.request.post(`${apiURL}/inventory/products`, {
    headers: bearer(token),
    data: { sku: `DENIED-${randomUUID().slice(0, 8)}`, name: 'Forbidden product', unitPrice: 10, reorderLevel: 0 },
  });
  expect(response.status()).toBe(403);
  const product = (await products(page, token)).find(item => item.sku === 'SKU-ELEC-001')!;
  const adjustment = await page.request.post(`${apiURL}/inventory/products/${product.id}/adjust`, {
    headers: bearer(token), data: { quantityAdjustment: 1, reasonCode: 'CYCLIC_COUNT_DISCREPANCY' },
  });
  expect(adjustment.status()).toBe(403);
  expect((await products(page, token)).find(item => item.id === product.id)?.stockQuantity).toBe(product.stockQuantity);
  await navigate(page, 'Purchase Orders');
  await expect(page.getByRole('button', { name: 'Create Purchase Order' })).toBeVisible();
});
