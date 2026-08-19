import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listScenarios, runScenario, resetDemoEnvironment, type ScenarioName } from '../api/scenarios';
import { ApiRequestError } from '../api/client';

// frontend-design.md §12.3: "This should be the centerpiece." Cards are rendered entirely from
// GET /demo/scenarios (name/explanation/demonstrates/expected behavior) rather than hardcoded, so
// the UI can't drift from docs/scenarios.md — see docs/openapi/scenario-service.yaml's rationale
// for serving this instead of hardcoding it.
export function ScenariosPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data: scenarios, isLoading, isError, error } = useQuery({
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

  const resetMutation = useMutation({
    mutationFn: resetDemoEnvironment,
    onSuccess: () => {
      queryClient.invalidateQueries();
    },
  });

  const runError =
    runMutation.error instanceof ApiRequestError ? runMutation.error.apiError.message : runMutation.error?.message;

  return (
    <section>
      <div className="page-header">
        <h1>Scenarios</h1>
        <button onClick={() => resetMutation.mutate()} disabled={resetMutation.isPending}>
          {resetMutation.isPending ? 'Resetting…' : 'Reset demo environment'}
        </button>
      </div>
      <p className="hint">
        Each card below is rendered live from <code>GET /demo/scenarios</code>. Running one issues
        real HTTP requests, real Kafka records, and real persistence changes against the four
        business services — nothing here is animated.
      </p>

      {isLoading && <p>Loading scenarios…</p>}
      {isError && (
        <p className="error">
          Could not reach Scenario Service: {(error as Error).message}. Is it running on the
          configured URL?
        </p>
      )}
      {runError && <p className="error">{runError}</p>}
      {resetMutation.isError && (
        <p className="error">
          {resetMutation.error instanceof ApiRequestError
            ? resetMutation.error.apiError.message
            : (resetMutation.error as Error).message}
        </p>
      )}
      {resetMutation.isSuccess && <p className="hint">Demo environment reset.</p>}

      <div className="scenario-grid">
        {scenarios?.map((scenario) => (
          <article key={scenario.name} className="scenario-card">
            <div className="scenario-card-header">
              <h2>{scenario.title}</h2>
              {!scenario.available && <span className="badge badge-muted">Not available yet</span>}
            </div>
            <p>{scenario.description}</p>

            <h3>Demonstrates</h3>
            <ul>
              {scenario.demonstrates.map((d) => (
                <li key={d}>{d}</li>
              ))}
            </ul>

            {scenario.expectedTerminalStatus && (
              <p className="hint">
                Expected terminal state: <strong>{scenario.expectedTerminalStatus}</strong>
              </p>
            )}

            <button
              onClick={() => runMutation.mutate(scenario.name)}
              disabled={!scenario.available || runMutation.isPending}
              title={scenario.available ? undefined : 'Not implemented in this build yet'}
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
