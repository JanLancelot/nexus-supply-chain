import { randomBytes } from 'node:crypto';
import { defineConfig, devices } from '@playwright/test';

// Generated once in the runner and inherited by both workers and the test server.
// Nothing is stored in a browser state file or committed as a credential.
process.env.E2E_ADMIN_PASSWORD ??= randomBytes(24).toString('hex');
process.env.E2E_STAFF_PASSWORD ??= randomBytes(24).toString('hex');
process.env.E2E_JWT_SECRET ??= randomBytes(48).toString('hex');

export default defineConfig({
  testDir: './e2e/workflows',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  outputDir: './test-results/e2e',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/e2e', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    locale: 'en-US',
    timezoneId: 'UTC',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: [
    {
      command: '../bin/start-e2e-backend.sh',
      url: 'http://127.0.0.1:18080/api/health',
      reuseExistingServer: false,
      timeout: 180_000,
      stdout: 'pipe',
      gracefulShutdown: { signal: 'SIGTERM', timeout: 10_000 },
    },
    {
      command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173',
      env: { E2E_API_TARGET: 'http://127.0.0.1:18080' },
      reuseExistingServer: false,
      timeout: 60_000,
      stdout: 'pipe',
    },
  ],
});
