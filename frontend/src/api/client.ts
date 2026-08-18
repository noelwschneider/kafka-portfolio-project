// Phase 3 split the monolith into four independent services; the frontend only ever calls Order
// Service (create/list/detail order) and Inventory Service (SKU picker) directly — see
// docs/agent-reports/phase-3-boundary.md. Each gets its own base URL / port, matching the frozen
// docs/openapi/*.yaml servers: blocks (Order 8081, Inventory 8082).
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
export const INVENTORY_SERVICE_BASE_URL = import.meta.env.VITE_INVENTORY_SERVICE_URL ?? 'http://localhost:8082';

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
