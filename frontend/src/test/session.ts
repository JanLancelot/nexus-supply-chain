// Unsigned fixtures for client session tests; never accepted by the backend.
export function sessionToken(overrides: Record<string, unknown> = {}) {
  const payload = {
    sub: 'operator@example.com',
    userId: 'test-user',
    fullName: 'Test Operator',
    role: 'ROLE_ADMIN',
    exp: Math.floor(Date.now() / 1000) + 3600,
    ...overrides,
  };
  return `header.${btoa(JSON.stringify(payload))}.signature`;
}
