import { expect, type Page, type Response } from '@playwright/test';
import type { PagedResponse, Product } from '../../src/types';

export const apiURL = 'http://127.0.0.1:18080/api/v1';

export function passwordFor(role: 'admin' | 'staff'): string {
  const value = process.env[`E2E_${role.toUpperCase()}_PASSWORD`];
  if (!value) throw new Error(`The E2E harness must provide the ${role} account password.`);
  return value;
}

export function bearer(token: string) {
  return { Authorization: `Bearer ${token}` };
}

export async function login(
  page: Page,
  role: 'admin' | 'staff' = 'admin',
  credentials = { email: `${role}@example.test`, password: passwordFor(role) },
): Promise<string> {
  await page.goto('/login');
  await page.getByLabel('Email Address').fill(credentials.email);
  await page.getByLabel('Password', { exact: true }).fill(credentials.password);
  const responsePromise = page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/v1/auth/login' && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const body = await response.json() as { token: string };
  expect(typeof body.token).toBe('string');
  await expect(page).toHaveURL(role === 'admin' ? /\/dashboard$/ : /\/catalog$/);
  return body.token;
}

export async function navigate(page: Page, name: string, heading = name) {
  await page.getByRole('link', { name, exact: true }).click();
  await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
}

export function selectWithOption(page: Page, option: string) {
  return page.getByRole('combobox').filter({
    has: page.locator('option').filter({ hasText: new RegExp(`^${option}$`) }),
  });
}

export async function getJSON<T>(page: Page, token: string, path: string): Promise<T> {
  const response = await page.request.get(`${apiURL}${path}`, { headers: bearer(token) });
  expect(response.status()).toBe(200);
  return response.json() as Promise<T>;
}

export async function products(page: Page, token: string): Promise<Product[]> {
  return (await getJSON<PagedResponse<Product>>(page, token, '/inventory/products?size=50')).content;
}

export function mutation(page: Page, method: string, path: string): Promise<Response> {
  return page.waitForResponse(response =>
    new URL(response.url()).pathname === `/api/v1${path}` && response.request().method() === method,
  );
}
