export const AUTH_EXPIRED_EVENT = 'yanban:auth-expired';

const ACCESS_TOKEN_KEY = 'yanban_access_token';
const REFRESH_TOKEN_KEY = 'yanban_refresh_token';

export function isJwtExpired(token: string | null, clockSkewSeconds = 15) {
  if (!token) return true;
  try {
    const payload = token.split('.')[1];
    if (!payload) return true;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const decoded = JSON.parse(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '='))) as { exp?: number };
    if (typeof decoded.exp !== 'number') return false;
    return decoded.exp <= Math.floor(Date.now() / 1000) + clockSkewSeconds;
  } catch {
    return true;
  }
}

export function expireAuthSession() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT));
}
