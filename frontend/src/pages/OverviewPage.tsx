import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fetchAllServiceHealth, type ServiceHealth } from '../api/health';
import { listOrders } from '../api/orders';
import { listScenarioRuns, runScenario, type ScenarioName } from '../api/scenarios';
import { StatusBadge } from '../components/StatusBadge';

// frontend-design.md §12.1: four business services + Kafka + PostgreSQL, plus Scenario Service
// (added in this phase). Kafka/PostgreSQL have no HTTP health endpoint of their own reachable from
// the browser, so they're reported through each service's Actuator health, which typically
// includes a `kafka` and a `db` component — see the note rendered on the page itself.
const QUICK_SCENARIOS: { name: ScenarioName; label: string }[] = [
  { name: 'standard-order', label: 'Standard Fulfillment' },
  { name: 'out-of-stock', label: 'Inventory Outage' },
  { name: 'duplicate-event', label: 'Duplicate Event' },
  { name: 'payment-failure', label: 'Payment Rejection' },
];

function stateLabel(health: ServiceHealth | undefined): string {
  if (!health) return 'checking…';
  switch (health.state) {
    case 'UP':
      return 'Healthy';
    case 'DOWN':
      return 'Down';
    case 'UNREACHABLE':
      return 'Unreachable';
    case 'UNKNOWN':
      return health.raw?.status ?? 'Unknown';
    default:
      return 'checking…';
  }
}

function stateClass(health: ServiceHealth | undefined): string {
  if (!health) return 'status status-pending';
  if (health.state === 'UP') return 'status status-success';
  if (health.state === 'CHECKING') return 'status status-pending';
  return 'status status-failure';
}

// Derives Kafka/PostgreSQL rows from whichever service's Actuator response carries those
// components, rather than fabricating a status with nothing behind it. If no reachable service
// reports a `kafka`/`db` component, the row honestly says so.
function deriveInfraStatus(healths: ServiceHealth[], componentKey: string): { state: string; source: string | null } {
  for (const h of healths) {
    const component = h.raw?.components?.[componentKey];
    if (component) {
      return { state: component.status, source: h.name };
    }
  }
  return { state: 'no data', source: null };
}

export function OverviewPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data: healths } = useQuery({
    queryKey: ['overview-health'],
    queryFn: fetchAllServiceHealth,
    refetchInterval: 10_000,
  });

  const { data: orders, isError: ordersError } = useQuery({
    queryKey: ['orders'],
    queryFn: listOrders,
    retry: false,
  });

  const { data: runs } = useQuery({
    queryKey: ['scenario-runs', 'recent'],
    queryFn: () => listScenarioRuns({ size: 5 }),
    retry: false,
  });

  const runMutation = useMutation({
    mutationFn: runScenario,
    onSuccess: (run) => {
      queryClient.invalidateQueries({ queryKey: ['scenario-runs'] });
      navigate(`/scenario-runs/${run.id}`);
    },
  });

  const healthByName = new Map((healths ?? []).map((h) => [h.name, h]));
  const kafka = deriveInfraStatus(healths ?? [], 'kafka');
  const db = deriveInfraStatus(healths ?? [], 'db');

  return (
    <section>
      <div className="overview-hero">
        <h1>Order Fulfillment Systems Lab</h1>
        <p className="overview-lede">
          A distributed order-fulfillment sandbox: four Spring Boot services (Order, Inventory,
          Payment, Fulfillment) coordinate exclusively through Kafka — no service calls another
          business service directly. A dedicated Scenario Service drives real, reproducible failure
          and recovery scenarios (duplicate delivery, consumer outages, dead-lettering, inventory
          contention) against the same public APIs any client uses, so what you trigger here is the
          same code path a real order takes, not a UI animation.
        </p>
      </div>

      <h2>System Status</h2>
      <table className="status-table">
        <tbody>
          {['Order Service', 'Inventory Service', 'Payment Service', 'Fulfillment Service', 'Scenario Service'].map(
            (name) => {
              const health = healthByName.get(name);
              return (
                <tr key={name}>
                  <td>{name}</td>
                  <td>
                    <span className={stateClass(health)}>{stateLabel(health)}</span>
                  </td>
                </tr>
              );
            },
          )}
          <tr>
            <td>Kafka</td>
            <td>
              <span className={kafka.state === 'UP' ? 'status status-success' : 'status status-pending'}>
                {kafka.state}
                {kafka.source ? ` (via ${kafka.source})` : ''}
              </span>
            </td>
          </tr>
          <tr>
            <td>PostgreSQL</td>
            <td>
              <span className={db.state === 'UP' ? 'status status-success' : 'status status-pending'}>
                {db.state}
                {db.source ? ` (via ${db.source})` : ''}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <p className="hint">
        Statuses come from each service's real <code>/actuator/health</code> endpoint, polled every
        10s. Kafka/PostgreSQL rows are read from whichever service's health response includes those
        components — "no data" means no reachable service reported one yet, not that it's down.
      </p>

      <h2>Quick Scenarios</h2>
      <div className="quick-scenarios">
        {QUICK_SCENARIOS.map((s) => (
          <button
            key={s.name}
            onClick={() => runMutation.mutate(s.name)}
            disabled={runMutation.isPending}
          >
            {s.label}
          </button>
        ))}
        <button onClick={() => navigate('/scenarios')}>All scenarios →</button>
      </div>
      {runMutation.isError && <p className="error">{(runMutation.error as Error).message}</p>}

      <h2>Recent Orders</h2>
      {ordersError && <p className="hint">Order Service unreachable or not yet running.</p>}
      {orders && orders.content.length === 0 && <p>No orders yet.</p>}
      {orders && orders.content.length > 0 && (
        <table className="orders-table">
          <tbody>
            {orders.content.slice(0, 5).map((order) => (
              <tr key={order.id} className="order-row" onClick={() => navigate(`/orders/${order.id}`)}>
                <td>{order.id}</td>
                <td>
                  <StatusBadge status={order.status} />
                </td>
                <td>{new Date(order.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Recent Scenario Runs</h2>
      {!runs && <p className="hint">Scenario Service unreachable or not yet running.</p>}
      {runs && runs.content.length === 0 && <p>No scenario runs yet.</p>}
      {runs && runs.content.length > 0 && (
        <table className="orders-table">
          <tbody>
            {runs.content.map((run) => (
              <tr key={run.id} className="order-row" onClick={() => navigate(`/scenario-runs/${run.id}`)}>
                <td>{run.scenarioName}</td>
                <td>{run.status}</td>
                <td>{new Date(run.startedAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
