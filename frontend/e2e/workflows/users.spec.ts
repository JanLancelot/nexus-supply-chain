import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { User } from '../../src/types';
import { apiURL, bearer, getJSON, login, mutation, navigate } from './helpers';

test('an administrator validates and provisions a staff account that can sign in', async ({ page }) => {
  const adminToken = await login(page);
  const email = `browser-${randomUUID()}@example.test`;
  const password = randomUUID();
  await navigate(page, 'User Management');
  await page.getByRole('button', { name: 'Create User Account' }).click();
  await expect(page.getByText('Full name is required', { exact: true })).toBeVisible();
  await expect(page.getByText('Email address is required', { exact: true })).toBeVisible();
  await expect(page.getByText('Password is required', { exact: true })).toBeVisible();
  await page.getByPlaceholder('e.g. Jane Doe').fill('Browser Provisioned Staff');
  await page.getByPlaceholder('name@company.com').fill(email);
  await page.getByPlaceholder('Min 8 characters').fill('short');
  await page.getByRole('button', { name: 'Create User Account' }).click();
  await expect(page.getByText('Password must be at least 8 characters long', { exact: true })).toBeVisible();
  await page.getByPlaceholder('Min 8 characters').fill(password);
  const responsePromise = mutation(page, 'POST', '/users');
  await page.getByRole('button', { name: 'Create User Account' }).click();
  expect((await responsePromise).status()).toBe(201);
  await expect(page.getByText('Account for "Browser Provisioned Staff" was successfully created!')).toBeVisible();
  await page.getByPlaceholder('Search by name or email...').fill(email);
  const row = page.getByRole('row').filter({ hasText: email });
  await expect(row).toContainText('Browser Provisioned Staff');
  await expect(row).toContainText('STAFF');
  await expect(row).toContainText('Active');
  const user = (await getJSON<User[]>(page, adminToken, '/users')).find(item => item.email === email);
  expect(user).toMatchObject({ fullName: 'Browser Provisioned Staff', role: 'ROLE_STAFF', status: 'ACTIVE' });
  expect(user).not.toHaveProperty('passwordHash');
  expect(user).not.toHaveProperty('password');

  // A duplicate attempt must leave the original account usable.
  await page.getByPlaceholder('e.g. Jane Doe').fill('Duplicate Attempt');
  await page.getByPlaceholder('name@company.com').fill(email);
  await page.getByPlaceholder('Min 8 characters').fill(randomUUID());
  const duplicateResponse = mutation(page, 'POST', '/users');
  await page.getByRole('button', { name: 'Create User Account' }).click();
  expect((await duplicateResponse).status()).toBe(400);
  await expect(page.getByText('Email is already registered', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Sign Out' }).click();
  const staffToken = await login(page, 'staff', { email, password });
  await expect(page.getByRole('heading', { name: 'Product Catalog' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'User Management' })).toHaveCount(0);
  const denied = await page.request.get(`${apiURL}/users`, { headers: bearer(staffToken) });
  expect(denied.status()).toBe(403);
});
