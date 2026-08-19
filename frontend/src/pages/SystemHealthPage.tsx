import { useQuery } from '@tanstack/react-query';
import { fetchAllServiceHealth, type ServiceHealth } from '../api/health';

// frontend-design.md §12.6. Everything here comes from real /actuator/health responses — same
// honesty rule as the Overview page's status list. Consumer status is deliberately omitted: the
// four services don't expose consumer-group lag/status via any frozen endpoint today
// (docs/agent-reports/phase-4-pattern-design.md's /demo/consumers is a pause/resume control, not a
// lag readout), so this page says so rather than inventing a number.
function HealthCard({ health }: { health: ServiceHealth }) {
  const badgeClass =
    health.state === 'UP' ? 'status-success' : health.state === 'CHECKING' ? 'status-pending' : 'status-failure';

  return (
    <article className="health-card">
      <div className="health-card-header">
        <h2>{health.name}</h2>
        <span className={`status ${badgeClass}`}>{health.state}</span>
      </div>
      <p className="hint">{health.baseUrl}</p>

      {health.errorMessage && <p className="error">{health.errorMessage}</p>}

      {health.raw?.components && (
        <table>
          <thead>
            <tr>
              <th>Component</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(health.raw.components).map(([name, component]) => (
              <tr key={name}>
                <td>{name}</td>
                <td>
                  <span
                    className={`status ${component.status === 'UP' ? 'status-success' : 'status-failure'}`}
                  >
                    {component.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!health.raw && !health.errorMessage && <p className="hint">No health data yet.</p>}

      <p className="hint">Checked {new Date(health.checkedAt).toLocaleTimeString()}</p>
    </article>
  );
}

export function SystemHealthPage() {
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: ['system-health'],
    queryFn: fetchAllServiceHealth,
    refetchInterval: 10_000,
  });

  return (
    <section>
      <div className="page-header">
        <h1>System Health</h1>
      </div>
      <p className="hint">
        Polled from each service's real <code>/actuator/health</code> endpoint every 10 seconds.
        {dataUpdatedAt ? ` Last refresh ${new Date(dataUpdatedAt).toLocaleTimeString()}.` : ''}
      </p>

      <div className="not-wired-banner">
        <strong>Consumer status:</strong> not shown. No frozen endpoint currently exposes
        consumer-group lag or per-listener status for the running build — <code>/demo/consumers</code>
        is a pause/resume control used by Scenario Service, not a lag readout. Rather than invent a
        number, this page omits the row.
      </div>

      {isLoading && <p>Checking services…</p>}

      <div className="health-grid">
        {data?.map((health) => (
          <HealthCard key={health.name} health={health} />
        ))}
      </div>

      <h2>Recent errors</h2>
      <p className="hint">
        {data?.some((h) => h.state === 'UNREACHABLE' || h.state === 'DOWN')
          ? 'One or more services are reporting DOWN or are unreachable — see cards above for detail.'
          : 'No service is currently reporting DOWN or unreachable.'}
        {' '}This page does not maintain its own error log; it reflects only the current health
        snapshot above (no frozen endpoint exists yet for historical error records).
      </p>
    </section>
  );
}
