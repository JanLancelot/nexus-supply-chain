import { test, expect, signIn, navigate, capture } from './fixtures';

test('login screen', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: 'Nexus Supply Chain' })).toBeVisible();
  await capture(page, 'login.png');
});

test.describe('authentication failure', () => {
  test.use({ visualState: 'login-error' });
  test('login error is visible without obscuring the form', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel('Email Address').fill('ada@example.test');
    await page.getByLabel('Password').fill('visual fixture');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    await expect(page.getByText('Unable to sign in. Check your email and password.')).toBeVisible();
    await capture(page, 'login-error.png');
  });
});

test('dashboard metrics and charts', async ({ page }) => {
  await signIn(page);
  await expect(page.getByText('Warehouse Inventory Distribution', { exact: true })).toBeVisible();
  await expect(page.getByText('$128,450.75', { exact: true })).toBeVisible();
  await capture(page, 'dashboard.png');
});

test('notification center', async ({ page }) => {
  await signIn(page);
  await expect(page.getByText('Warehouse Inventory Distribution', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Notifications', exact: true }).click();
  await expect(page.getByText('Notifications (Total: 3)')).toBeVisible();
  await capture(page, 'notifications.png');
});

test.describe('failed dashboard request', () => {
  test.use({ visualState: 'dashboard-error' });
  test('dashboard error and retry action', async ({ page }) => {
    await signIn(page);
    await expect(page.getByRole('heading', { name: 'Failed to Load Metrics' })).toBeVisible();
    await capture(page, 'dashboard-error.png');
  });
});

test('catalog stock states', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
  await expect(page.getByRole('row').filter({ hasText: 'SAF-HELMET-W' })).toContainText('LOW STOCK');
  await capture(page, 'catalog.png');
});

test('catalog empty search result', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
  await page.getByPlaceholder('Search this page by SKU or name...').fill('missing product');
  await expect(page.getByRole('heading', { name: 'No Products Found' })).toBeVisible();
  await capture(page, 'catalog-empty.png');
});

test('new product dialog', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
  await page.getByRole('button', { name: 'New Product', exact: true }).click();
  await expect(page.getByText('Add New Catalog Product', { exact: true })).toBeVisible();
  await capture(page, 'catalog-create-dialog.png');
});

test('stock adjustment dialog', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
  await page.getByRole('row').filter({ hasText: 'SAF-GLOVE-M' }).getByRole('button', { name: 'Adjust', exact: true }).click();
  await expect(page.getByText('Current Stock: 8 items', { exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. -12 or 50').fill('-2');
  await capture(page, 'catalog-adjust-dialog.png');
});

test('purchase order list covers all statuses', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Purchase Orders', 'PO-2026-001');
  await expect(page.getByRole('row')).toHaveCount(7);
  await capture(page, 'orders.png');
});

test('purchase order wizard with line items', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Purchase Orders', 'PO-2026-001');
  await page.getByRole('button', { name: 'Create Purchase Order', exact: true }).click();
  await expect(page.getByText('New Purchase Order', { exact: true })).toBeVisible();
  const form = page.locator('form');
  await form.getByRole('combobox').nth(0).selectOption({ label: 'Pacific Trade Supplies' });
  await form.getByRole('combobox').nth(1).selectOption('warehouse-manila');
  await form.getByRole('combobox').nth(2).selectOption('product-001');
  await page.getByPlaceholder('Qty').fill('10');
  await page.getByRole('button', { name: 'Add Line', exact: true }).click();
  await form.getByRole('combobox').nth(3).selectOption('product-003');
  await page.getByPlaceholder('Qty').nth(1).fill('40');
  await expect(page.getByText('$195.50', { exact: true })).toBeVisible();
  await capture(page, 'order-wizard.png');
});

test('purchase order inspection and approval actions', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Purchase Orders', 'PO-2026-001');
  await page.getByRole('row').filter({ hasText: 'PO-2026-002' }).getByRole('button', { name: 'Inspect', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Approve Order', exact: true })).toBeVisible();
  await capture(page, 'order-inspection.png');
});

test('audit history', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Audit Logs', 'ID: product-002');
  await capture(page, 'audit-logs.png');
});

test('audit change comparison', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Audit Logs', 'ID: product-002');
  await page.getByRole('row').filter({ hasText: 'ID: product-002' }).click();
  await expect(page.getByText('Log Snapshot Details', { exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'stockQuantity', exact: true })).toBeVisible();
  await capture(page, 'audit-comparison.png');
});

test('user directory and account form', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'User Management', 'alexandra@example.test');
  await capture(page, 'users.png');
});

test('account form validation', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'User Management', 'alexandra@example.test');
  await page.getByRole('button', { name: 'Create User Account', exact: true }).click();
  await expect(page.getByText('Full name is required', { exact: true })).toBeVisible();
  await expect(page.getByText('Email address is required', { exact: true })).toBeVisible();
  await expect(page.getByText('Password is required', { exact: true })).toBeVisible();
  await capture(page, 'users-validation.png');
});

test.describe('staff access', () => {
  test.use({ visualRole: 'ROLE_STAFF' });
  test('staff catalog omits administration actions', async ({ page }) => {
    await signIn(page, 'staff');
    await expect(page.getByText('OFF-PAPER-A4', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'New Product', exact: true })).toHaveCount(0);
    await capture(page, 'staff-catalog.png');
  });
});

test.describe('narrow viewport', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('login at phone width', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Nexus Supply Chain' })).toBeVisible();
    await capture(page, 'login-narrow.png');
  });

  test('catalog at phone width', async ({ page }) => {
    await signIn(page);
    await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
    await expect.poll(
      () => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
      { message: 'The phone layout must contain overflow inside tables, not scroll the entire page horizontally' },
    ).toBe(true);
    await expect(page.getByPlaceholder('Search this page by SKU or name...')).toBeInViewport({ ratio: 1 });
    await expect(page.getByRole('button', { name: 'New Product', exact: true })).toBeInViewport({ ratio: 1 });
    await page.getByRole('button', { name: 'New Product', exact: true }).click();
    await expect(page.getByText('Add New Catalog Product', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create Product', exact: true })).toBeInViewport({ ratio: 1 });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await capture(page, 'catalog-narrow.png');
  });
});

test('notifications fit a small phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await signIn(page);
  await page.getByRole('button', { name: 'Notifications', exact: true }).click();
  await expect(page.getByText('Notifications (Total: 3)')).toBeInViewport({ ratio: 1 });
  await expect(page.getByRole('button', { name: 'Read All', exact: true })).toBeInViewport({ ratio: 1 });
  await expect(page.getByText('Protective Work Gloves — Medium is below the reorder level.')).toBeInViewport({ ratio: 1 });
  await capture(page, 'notifications-narrow.png');
});

for (const width of [320, 390]) {
  test(`dashboard mobile overview at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await signIn(page);
    await expect(page.getByText('$128,450.75', { exact: true })).toBeInViewport({ ratio: 1 });
    await expect(page.getByText('$245,630.50', { exact: true })).toBeInViewport({ ratio: 1 });
    await expect(page.getByText('All order statuses', { exact: true })).toBeInViewport({ ratio: 1 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await capture(page, `dashboard-mobile-${width}.png`);
  });
}

test('mobile menu exposes every destination and closes after navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  const menu = page.getByRole('button', { name: 'Menu', exact: true });
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeHidden();
  await menu.click();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  for (const name of ['Dashboard', 'User Management', 'Product Catalog', 'Purchase Orders', 'Reference Data', 'Audit Logs', 'Monitoring']) {
    await expect(page.getByRole('link', { name, exact: true })).toBeInViewport({ ratio: 1 });
  }
  await expect(page.getByRole('button', { name: 'Sign Out' })).toBeInViewport({ ratio: 1 });
  await capture(page, 'mobile-menu.png');
  await page.getByRole('link', { name: 'Product Catalog', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Product Catalog', exact: true })).toBeVisible();
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeHidden();
});

test('monitoring dashboard entry and separate operator login', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Monitoring', 'Understand your operations over time');
  const grafana = page.getByRole('link', { name: /Open Grafana/ });
  await expect(grafana).toHaveAttribute('href', 'https://monitoring.example.test/');
  await expect(grafana).toHaveAttribute('target', '_blank');
  await expect(grafana).toHaveAttribute('rel', 'noopener noreferrer');
  await capture(page, 'monitoring.png');
});

test('monitoring fits a small phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await signIn(page);
  await navigate(page, 'Monitoring', 'Understand your operations over time');
  await expect(page.getByRole('link', { name: /Open Grafana/ })).toBeInViewport({ ratio: 1 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
  await capture(page, 'monitoring-narrow.png');
});

test.describe('monitoring setup', () => {
  test.use({ visualState: 'monitoring-disabled' });
  test('explains when dashboards have not been connected', async ({ page }) => {
    await signIn(page);
    await navigate(page, 'Monitoring', 'Monitoring is not configured yet');
    await capture(page, 'monitoring-disabled.png');
  });
});

test.describe('monitoring failure', () => {
  test.use({ visualState: 'monitoring-error' });
  test('shows an error with a retry action', async ({ page }) => {
    await signIn(page);
    await navigate(page, 'Monitoring', 'Unable to load monitoring. Please try again.');
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
    await capture(page, 'monitoring-error.png');
  });
});


test('reference data setup and supplier sourcing', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Reference Data', 'Set up your supply chain');
  await expect(page.getByRole('button', { name: 'Create supplier', exact: true })).toBeVisible();
  await capture(page, 'reference-data.png');
});


test('catalog errors remain readable inside the product dialog', async ({ page }) => {
  await signIn(page);
  await navigate(page, 'Product Catalog', 'OFF-PAPER-A4');
  await page.getByRole('button', { name: 'New Product', exact: true }).click();
  await page.getByPlaceholder('e.g. SKU-OFFICE-002').fill('OFF-PAPER-A4');
  await page.getByPlaceholder('e.g. 12.99').fill('0');
  await page.getByPlaceholder('e.g. Copy Paper A4 500 Sheets').fill('Sample paper');
  await page.getByRole('dialog').getByRole('combobox').nth(1).selectOption('warehouse-manila');
  await page.getByRole('button', { name: 'Create Product', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toHaveText('SKU already exists: OFF-PAPER-A4');
  await capture(page, 'catalog-create-error.png');
});

test.describe('staff pending order', () => {
  test.use({ visualRole: 'ROLE_STAFF' });
  test('staff can cancel while awaiting approval', async ({ page }) => {
    await signIn(page, 'staff');
    await navigate(page, 'Purchase Orders', 'PO-2026-001');
    await page.getByRole('row').filter({ hasText: 'PO-2026-002' }).getByRole('button', { name: 'Inspect', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Cancel Order', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve Order', exact: true })).toHaveCount(0);
    await capture(page, 'staff-pending-order.png');
  });
});
