// Phase 3 split the monolith into four independent services; Phase 5 adds a fifth, Scenario
// Service, as the demo control plane. Each service gets its own base URL / port, matching the
// frozen docs/openapi/*.yaml servers: blocks (Order 8081, Inventory 8082, Payment 8083,
// Fulfillment 8084, Scenario 8085) — see docs/agent-reports/phase-3-boundary.md and
// docs/openapi/scenario-service.yaml.
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
export const INVENTORY_SERVICE_BASE_URL = import.meta.env.VITE_INVENTORY_SERVICE_URL ?? 'http://localhost:8082';
export const PAYMENT_SERVICE_BASE_URL = import.meta.env.VITE_PAYMENT_SERVICE_URL ?? 'http://localhost:8083';
export const FULFILLMENT_SERVICE_BASE_URL = import.meta.env.VITE_FULFILLMENT_SERVICE_URL ?? 'http://localhost:8084';
export const SCENARIO_SERVICE_BASE_URL = import.meta.env.VITE_SCENARIO_SERVICE_URL ?? 'http://localhost:8085';

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  correlationId: string | null;
}

export class ApiRequestError extends Error {
  readonly apiError: ApiError;

  constructor(apiError: ApiError) {
    super(apiError.message);
    this.apiError = apiError;
  }
}

export async function apiFetch<T>(baseUrl: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.json();
  if (!response.ok) {
    throw new ApiRequestError(body as ApiError);
  }
  return body as T;
}

/**
 * Thin, browser-native SSE wrapper (frontend-design.md's Live Frontend Updates section: no
 * libraries, native EventSource only). Subscribes to one or more named SSE event types on a
 * stream, invoking `onMessage` for each, and returns an unsubscribe function.
 *
 * Deliberately dumb: no reconnection/backoff policy beyond what EventSource itself does (it
 * auto-reconnects on a dropped connection by default), no buffering. Callers that need a polling
 * fallback (e.g. because the stream endpoint isn't implemented yet, or drops permanently) wire
 * that themselves via `onError`.
 */
export function subscribeToStream(
  url: string,
  handlers: {
    onMessage: (eventName: string, data: string) => void;
    onOpen?: () => void;
    onError?: (event: Event) => void;
  },
  eventNames: string[],
): () => void {
  const source = new EventSource(url);

  if (handlers.onOpen) {
    source.addEventListener('open', handlers.onOpen);
  }
  if (handlers.onError) {
    source.addEventListener('error', handlers.onError);
  }

  const listeners = eventNames.map((name) => {
    const listener = (event: MessageEvent) => handlers.onMessage(name, event.data);
    source.addEventListener(name, listener as EventListener);
    return { name, listener };
  });

  return () => {
    for (const { name, listener } of listeners) {
      source.removeEventListener(name, listener as EventListener);
    }
    source.close();
  };
}
