// Spring Boot Actuator health, per docs/openapi/order-service.yaml's note: "Health and metrics are
// exposed by Spring Boot Actuator (/actuator/health, ...) and are deliberately outside this
// document." There is no frozen contract for the response shape beyond Actuator's own default —
// {"status": "UP"|"DOWN"|..., "components": {...}} — so this client is defensive about shape and
// never invents a status the response didn't report.
//
// This file backs both the Overview page's System Status list and the System Health page. Per the
// phase-5 task's honesty rule, a service that can't be reached is reported as "unreachable", never
// silently shown as healthy.
import {
  ORDER_SERVICE_BASE_URL,
  INVENTORY_SERVICE_BASE_URL,
  PAYMENT_SERVICE_BASE_URL,
  FULFILLMENT_SERVICE_BASE_URL,
  SCENARIO_SERVICE_BASE_URL,
} from './client';

export type HealthState = 'UP' | 'DOWN' | 'UNKNOWN' | 'UNREACHABLE' | 'CHECKING';

export interface ActuatorComponent {
  status: string;
  details?: Record<string, unknown>;
}

export interface ActuatorHealth {
  status: string;
  components?: Record<string, ActuatorComponent>;
}

export interface ServiceHealth {
  name: string;
  baseUrl: string;
  state: HealthState;
  raw: ActuatorHealth | null;
  errorMessage: string | null;
  checkedAt: string;
}

export const MONITORED_SERVICES: { name: string; baseUrl: string }[] = [
  { name: 'Order Service', baseUrl: ORDER_SERVICE_BASE_URL },
  { name: 'Inventory Service', baseUrl: INVENTORY_SERVICE_BASE_URL },
  { name: 'Payment Service', baseUrl: PAYMENT_SERVICE_BASE_URL },
  { name: 'Fulfillment Service', baseUrl: FULFILLMENT_SERVICE_BASE_URL },
  { name: 'Scenario Service', baseUrl: SCENARIO_SERVICE_BASE_URL },
];

/**
 * Fetches GET {baseUrl}/actuator/health. Never throws: a network failure, non-2xx response, or
 * unparsable body all come back as a ServiceHealth with state UNREACHABLE/UNKNOWN and an
 * errorMessage, so callers can render an honest state instead of catching everywhere.
 */
export async function fetchServiceHealth(name: string, baseUrl: string): Promise<ServiceHealth> {
  const checkedAt = new Date().toISOString();
  try {
    const response = await fetch(`${baseUrl}/actuator/health`);
    if (!response.ok) {
      return {
        name,
        baseUrl,
        state: 'UNREACHABLE',
        raw: null,
        errorMessage: `HTTP ${response.status}`,
        checkedAt,
      };
    }
    const body = (await response.json()) as ActuatorHealth;
    const state: HealthState = body.status === 'UP' ? 'UP' : body.status === 'DOWN' ? 'DOWN' : 'UNKNOWN';
    return { name, baseUrl, state, raw: body, errorMessage: null, checkedAt };
  } catch (err) {
    return {
      name,
      baseUrl,
      state: 'UNREACHABLE',
      raw: null,
      errorMessage: err instanceof Error ? err.message : 'Network error',
      checkedAt,
    };
  }
}

export function fetchAllServiceHealth(): Promise<ServiceHealth[]> {
  return Promise.all(MONITORED_SERVICES.map((s) => fetchServiceHealth(s.name, s.baseUrl)));
}
