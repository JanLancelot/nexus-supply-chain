import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { AxiosError } from 'axios';
import api from '../src/services/api.ts';
import { getErrorMessage } from '../src/services/errors.ts';
import { getAccessToken, getSession, parseSession, setSession, subscribeSession } from '../src/services/session.ts';

const claims = (overrides = {}) => ({
  userId: 'test-user', sub: 'user@example.test', fullName: 'José 李', role: 'ROLE_STAFF',
  exp: Math.floor(Date.now() / 1000) + 300, ...overrides,
});
const tokenFor = data => `header.${Buffer.from(JSON.stringify(data)).toString('base64url')}.signature`;
const signIn = (overrides = {}) => {
  const session = parseSession(tokenFor(claims(overrides)));
  assert.ok(session);
  setSession(session);
  return session;
};
afterEach(() => setSession(null));

test('decodes UTF-8 profile claims without corrupting names', () => {
  const session = parseSession(tokenFor(claims()));
  assert.equal(session.user.fullName, 'José 李');
  assert.equal(session.user.role, 'ROLE_STAFF');
});

test('rejects expired, malformed, and incomplete session payloads', () => {
  for (const token of [null, '', 'bad.token', 'a.@@.b', tokenFor(null), tokenFor([]),
    tokenFor(claims({ exp: 1 })), tokenFor(claims({ exp: '300' })),
    tokenFor(claims({ userId: null })), tokenFor(claims({ fullName: {} })),
    tokenFor(claims({ role: 'SUPERADMIN' }))]) {
    assert.equal(parseSession(token), null);
  }
});

test('expiry invalidates the subscribed UI session and request token', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000_000 });
  let updates = 0;
  const unsubscribe = subscribeSession(() => updates++);
  signIn({ exp: 1001 });
  assert.ok(getAccessToken());
  t.mock.timers.tick(1001);
  assert.equal(getSession(), null);
  assert.equal(getAccessToken(), null);
  assert.equal(updates, 2);
  unsubscribe();
});

test('expired token is not attached after the system clock advances', (t) => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'], now: 1_000_000 });
  signIn({ exp: 1001 });
  t.mock.timers.setTime(1_002_000);
  assert.equal(getAccessToken(), null);
});

test('API attaches only the active session and clears it on its own 401', async () => {
  const session = signIn();
  await assert.rejects(api.get('/orders', { adapter: config => {
    assert.equal(config.headers.Authorization, `Bearer ${session.token}`);
    return Promise.reject(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined,
      { status: 401, statusText: 'Unauthorized', config, headers: {}, data: {} }));
  }}));
  assert.equal(getSession(), null);
});

test('late unauthorized response from an older session does not log out the new user', async () => {
  signIn();
  let newerSession;
  await assert.rejects(api.get('/orders', { adapter: config => {
    newerSession = signIn({ userId: 'another-user' });
    return Promise.reject(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined,
      { status: 401, statusText: 'Unauthorized', config, headers: {}, data: {} }));
  }}));
  assert.equal(getSession(), newerSession);
});

test('anonymous requests do not attach a bearer token', async () => {
  await api.get('/health', { adapter: config => {
    assert.equal(config.headers.Authorization, undefined);
    return Promise.resolve({ status: 200, statusText: 'OK', config, headers: {}, data: {} });
  }});
});

test('error display accepts only an API message string, never request config or arbitrary objects', () => {
  const failure = new AxiosError('internal detail');
  failure.response = { data: { message: { detail: 'not a string' } } };
  assert.equal(getErrorMessage(failure, 'Try again'), 'Try again');
  failure.response.data.message = 'Validation failed';
  assert.equal(getErrorMessage(failure, 'Try again'), 'Validation failed');
  assert.equal(getErrorMessage(new Error('internal detail'), 'Try again'), 'Try again');
});
