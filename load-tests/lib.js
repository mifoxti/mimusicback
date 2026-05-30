import http from 'k6/http';
import { check } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
export const LOGIN = __ENV.K6_LOGIN || 'mifoxti';
export const PASSWORD = __ENV.K6_PASSWORD || 'MiMusic';
/** 1 = отдельный логин на каждый VU: loadtest1, loadtest2, … (см. seed-load-users.ps1) */
export const MULTI_USER = (__ENV.K6_MULTI_USER || '1') === '1';

function parseLoginBody(res) {
  try {
    const body = res.json();
    return body && body.token ? String(body.token) : null;
  } catch (_) {
    return null;
  }
}

/** Логин для текущего виртуального пользователя (при MULTI_USER — loadtest{VU}). */
export function vuCredentials() {
  if (!MULTI_USER) {
    return { login: LOGIN, password: PASSWORD };
  }
  const n = __VU;
  return { login: `loadtest${n}`, password: PASSWORD };
}

export function loginWith(creds) {
  const res = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ login: creds.login, password: creds.password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } },
  );
  const token = parseLoginBody(res);
  check(res, {
    'login status is 200': (r) => r.status === 200,
    'login has token': () => token != null && token.length > 0,
  });
  return token;
}

/** @deprecated используйте sessionToken() */
export function login() {
  return loginWith(vuCredentials());
}

let cachedToken = null;
let cachedForLogin = null;

/**
 * Один токен на VU за весь прогон (без повторного POST /login каждую итерацию).
 * При K6_MULTI_USER=1 у каждого VU свой аккаунт — сессии не затирают друг друга.
 */
export function sessionToken() {
  const creds = vuCredentials();
  if (cachedToken && cachedForLogin === creds.login) {
    return cachedToken;
  }
  cachedToken = loginWith(creds);
  cachedForLogin = creds.login;
  return cachedToken;
}

export function authParams(token) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  };
}
