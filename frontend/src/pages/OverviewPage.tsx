import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fetchAllServiceHealth, type ServiceHealth } from '../api/health';
import { listScenarios, runScenario, type ScenarioName } from '../api/scenarios';
import { ApiRequestError } from '../api/client';
import { LoadingHint } from '../components/LoadingHint';

// frontend-design.md §12.1: four business services + Kafka + PostgreSQL, plus Scenario Service
// (added in this phase). Kafka/PostgreSQL have no HTTP health endpoint of their own reachable from
// the browser, so they're reported through each service's Actuator health, which includes a
// `kafka` and a `db` component once `management.endpoint.health.show-components: always` is set.

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

// "no data" means no reachable service has reported this component yet — a benign, designed
// absence, not a fault — so it gets the same "expected" treatment as REJECTED_OUT_OF_STOCK/
// PAYMENT_FAILED elsewhere in the app, distinct from both a healthy UP and an actual reported
// failure (e.g. a component whose own status came back DOWN).
function infraClass(state: string): string {
  if (state === 'UP') return 'status status-success';
  if (state === 'no data') return 'status status-expected';
  return 'status status-failure';
}

export function OverviewPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data: healths, isLoading: healthsLoading } = useQuery({
    queryKey: ['overview-health'],
    queryFn: fetchAllServiceHealth,
    refetchInterval: 10_000,
  });

  const { data: scenarios, isLoading: scenariosLoading, isError: scenariosError, error: scenariosErrorObj } = useQuery({
    queryKey: ['scenarios'],
    queryFn: listScenarios,
  });

  const runMutation = useMutation({
    mutationFn: (name: ScenarioName) => runScenario(name),
    onSuccess: (run) => {
      queryClient.invalidateQueries({ queryKey: ['scenario-runs'] });
      navigate(`/scenario-runs/${run.id}`);
    },
  });

  const runError =
    runMutation.error instanceof ApiRequestError ? runMutation.error.apiError.message : runMutation.error?.message;

  const healthByName = new Map((healths ?? []).map((h) => [h.name, h]));
  const kafka = deriveInfraStatus(healths ?? [], 'kafka');
  const db = deriveInfraStatus(healths ?? [], 'db');

  return (
    <section>
      <h2>System Status</h2>
      {healthsLoading && <LoadingHint label="Loading service health…" />}
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
              <span className={infraClass(kafka.state)}>
                {kafka.state}
                {kafka.source ? ` (via ${kafka.source})` : ''}
              </span>
            </td>
          </tr>
          <tr>
            <td>PostgreSQL</td>
            <td>
              <span className={infraClass(db.state)}>
                {db.state}
                {db.source ? ` (via ${db.source})` : ''}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <p className="hint">"No data" means none has reported yet, not that it's down.</p>

      <h2>Scenarios</h2>
      <p className="hint">See how the system handles a variety of scenarios.</p>

      {scenariosLoading && <LoadingHint label="Loading scenarios…" />}
      {scenariosError && (
        <p className="error">Could not reach Scenario Service: {(scenariosErrorObj as Error).message}.</p>
      )}
      {runError && <p className="error">{runError}</p>}

      <div className="scenario-grid">
        {scenarios?.map((scenario) => (
          <article key={scenario.name} className="scenario-card">
            <div className="scenario-card-header">
              <h3>{scenario.title}</h3>
              {!scenario.available && <span className="badge badge-muted">Not available yet</span>}
            </div>
            <p className="scenario-card-description">{scenario.description}</p>

            <button
              className="button-primary"
              onClick={() => runMutation.mutate(scenario.name)}
              disabled={!scenario.available || runMutation.isPending}
              title={scenario.available ? undefined : 'Not implemented yet'}
            >
              {runMutation.isPending && runMutation.variables === scenario.name
                ? 'Starting…'
                : 'Run Scenario'}
            </button>
          </article>
        ))}
      </div>
    </section>
  );
}
