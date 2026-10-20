import type { ActionResult, Controller, Game, ServerInfo, User } from './types';
import {
  validateActionResult,
  validateControllers,
  validateGames,
  validateServerInfo,
  validateUsers,
} from './types';

const FETCH_TIMEOUT_MS = 5000;

export class AuthError extends Error {
  constructor() {
    super('Unauthorized');
    this.name = 'AuthError';
  }
}

type Validator<T> = (data: unknown) => T | null;

export function basicAuthorization(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return `Basic ${btoa(binary)}`;
}

function authHeaders(authorization: string | null, contentType?: string): Headers {
  const headers = new Headers();
  if (authorization) {
    headers.set('Authorization', authorization);
  }
  if (contentType) {
    headers.set('Content-Type', contentType);
  }
  return headers;
}

async function fetchJsonWithTimeout(
  path: string,
  authorization: string | null,
  signal?: AbortSignal
): Promise<unknown> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  const abortFromCaller = () => controller.abort();
  signal?.addEventListener('abort', abortFromCaller, { once: true });
  if (signal?.aborted) controller.abort();

  try {
    const response = await fetch(path, {
      signal: controller.signal,
      headers: authHeaders(authorization),
    });

    if (response.status === 401) {
      throw new AuthError();
    }

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    return await response.json();
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener('abort', abortFromCaller);
  }
}

async function fetchValidated<T>(
  endpoint: string,
  validator: Validator<T>,
  authorization: string | null,
  signal?: AbortSignal
): Promise<T | null> {
  try {
    const data = await fetchJsonWithTimeout(`/api/admin/${endpoint}`, authorization, signal);
    const validated = validator(data);

    if (validated === null) {
      console.error(`Validation failed for ${endpoint}: unexpected data shape`);
      return null;
    }

    return validated;
  } catch (error) {
    if (error instanceof AuthError) {
      throw error;
    }
    if (signal?.aborted) {
      return null;
    }
    if (error instanceof Error && error.name === 'AbortError') {
      console.error(`Request timeout for ${endpoint}`);
    } else {
      console.error(`Could not fetch data for ${endpoint}:`, error);
    }
    return null;
  }
}

export function fetchServerInfo(
  authorization: string,
  signal?: AbortSignal
): Promise<ServerInfo | null> {
  return fetchValidated('server-info', validateServerInfo, authorization, signal);
}

export function fetchUsers(authorization: string, signal?: AbortSignal): Promise<User[] | null> {
  return fetchValidated('users', validateUsers, authorization, signal);
}

export function fetchGames(authorization: string, signal?: AbortSignal): Promise<Game[] | null> {
  return fetchValidated('games', validateGames, authorization, signal);
}

export function fetchControllers(
  authorization: string,
  signal?: AbortSignal
): Promise<Controller[] | null> {
  return fetchValidated('controllers', validateControllers, authorization, signal);
}

export interface KickResponse {
  notFound: boolean;
  result: ActionResult | null;
}

export async function kickUser(
  userId: number,
  reason: string,
  authorization: string | null
): Promise<KickResponse> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);

  try {
    const response = await fetch(`/api/admin/users/${userId}/kick`, {
      method: 'POST',
      headers: authHeaders(authorization, 'application/json'),
      body: JSON.stringify({ reason }),
      signal: controller.signal,
    });

    if (response.status === 401) {
      throw new AuthError();
    }

    if (response.status === 404) {
      return { notFound: true, result: null };
    }

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const data: unknown = await response.json();
    return { notFound: false, result: validateActionResult(data) };
  } finally {
    clearTimeout(timeoutId);
  }
}
