import type { User } from '../types/index.ts';

export interface Session {
  token: string;
  user: User;
  expiresAt: number;
}

// Claims provide display data only; the API must verify signatures and permissions.
export function parseSession(token: unknown, now = Date.now()): Session | null {
  if (typeof token !== 'string' || token.split('.').length !== 3) return null;
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(payload), char => char.charCodeAt(0));
    const claims: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (typeof claims !== 'object' || claims === null) return null;
    const data = claims as Record<string, unknown>;
    if (typeof data.exp !== 'number' || !Number.isFinite(data.exp) ||
        data.exp * 1000 <= now || !Number.isSafeInteger(data.exp * 1000) ||
        typeof data.userId !== 'string' || !data.userId ||
        typeof data.sub !== 'string' || !data.sub ||
        (data.role !== 'ROLE_ADMIN' && data.role !== 'ROLE_STAFF') ||
        (data.fullName !== undefined && typeof data.fullName !== 'string')) return null;
    return {
      token,
      expiresAt: data.exp * 1000,
      user: {
        id: data.userId,
        email: data.sub,
        fullName: typeof data.fullName === 'string' && data.fullName.trim() ? data.fullName : data.sub,
        role: data.role,
        status: 'ACTIVE',
      },
    };
  } catch {
    return null;
  }
}

// Keep bearer credentials out of persistent browser storage.
let session: Session | null = null;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();

export const getSession = () => session;
export const subscribeSession = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};

export function setSession(next: Session | null) {
  if (expiryTimer !== undefined) clearTimeout(expiryTimer);
  session = next;
  if (next) {
    // Recheck instead of overflowing the browser's maximum timer delay.
    expiryTimer = setTimeout(() => {
      if (next.expiresAt <= Date.now()) setSession(null);
      else setSession(next);
    }, Math.min(Math.max(next.expiresAt - Date.now(), 0), 2_147_483_647));
  }
  listeners.forEach(listener => listener());
}

export function getAccessToken() {
  if (session && session.expiresAt <= Date.now()) setSession(null);
  return session?.token ?? null;
}
