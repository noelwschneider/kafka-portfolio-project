import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  getScenarioRun,
  scenarioRunStreamUrl,
  type ScenarioRun,
  type ScenarioTimelineEntry,
} from '../api/scenarios';
import { subscribeToStream } from '../api/client';

interface Props {
  runId: string;
  onBack: () => void;
}

const TERMINAL_RUN_STATUSES = new Set(['COMPLETED', 'FAILED']);

// docs/planning/sprint-1/frontend-design.md §12.4's frozen rule for this page: "Do not fabricate these
// fields. Display only values actually available from the system." detail is an open, all-optional
// object (docs/openapi/scenario-service.yaml), so this list is *candidate* keys to look for and
// label nicely — anything present that isn't in this list still renders via the fallback loop, and
// anything in this list that's absent is skipped entirely (never shown blank or as 0).
const KNOWN_DETAIL_FIELDS: { key: string; label: string }[] = [
  { key: 'phase', label: 'phase' },
  { key: 'topic', label: 'topic' },
  { key: 'partition', label: 'partition' },
  { key: 'offset', label: 'offset' },
  { key: 'eventId', label: 'eventId' },
  { key: 'correlationId', label: 'correlationId' },
  { key: 'aggregateId', label: 'aggregateId' },
  { key: 'producer', label: 'producer' },
  { key: 'consumer', label: 'consumer' },
  { key: 'durationMs', label: 'processing duration (ms)' },
  { key: 'retryCount', label: 'retry count' },
  { key: 'statusCode', label: 'statusCode' },
  { key: 'orderId', label: 'orderId' },
  { key: 'status', label: 'status' },
  { key: 'error', label: 'error' },
];

function TimelineEntryDetail({ entry }: { entry: ScenarioTimelineEntry }) {
  const [expanded, setExpanded] = useState(false);
  const detail = entry.detail;
  const hasDetail = detail && Object.keys(detail).length > 0;

  const knownKeys = new Set(KNOWN_DETAIL_FIELDS.map((f) => f.key));
  const extraKeys = detail ? Object.keys(detail).filter((k) => !knownKeys.has(k)) : [];

  return (
    <li className={`timeline-entry timeline-${entry.kind.toLowerCase()}`}>
      <div className="timeline-row" onClick={() => hasDetail && setExpanded((e) => !e)}>
        <span className="timeline-time">{new Date(entry.occurredAt).toLocaleTimeString(undefined, { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}.{new Date(entry.occurredAt).getMilliseconds().toString().padStart(3, '0')}</span>
        <span className="timeline-kind">{entry.kind}</span>
        <span className="timeline-label">{entry.label}</span>
        {hasDetail && <span className="timeline-expand">{expanded ? '▾' : '▸'}</span>}
      </div>
      {expanded && hasDetail && (
        <dl className="timeline-detail">
          {KNOWN_DETAIL_FIELDS.filter((f) => detail && detail[f.key] !== undefined && detail[f.key] !== null).map(
            (f) => (
              <div key={f.key} className="timeline-detail-row">
                <dt>{f.label}</dt>
                <dd>{String((detail as Record<string, unknown>)[f.key])}</dd>
              </div>
            ),
          )}
          {extraKeys.map((k) => (
            <div key={k} className="timeline-detail-row">
              <dt>{k}</dt>
              <dd>{String((detail as Record<string, unknown>)[k])}</dd>
            </div>
          ))}
        </dl>
      )}
    </li>
  );
}

export function ScenarioRunDetailPage({ runId, onBack }: Props) {
  const queryClient = useQueryClient();
  const [streamState, setStreamState] = useState<'connecting' | 'live' | 'unavailable'>('connecting');

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['scenario-run', runId],
    queryFn: () => getScenarioRun(runId),
    // Fallback poll — primary mechanism is the SSE stream below. Kept modest since scenario runs
    // are short-lived and this only matters if the stream never connects.
    refetchInterval: (query) => {
      const status = (query.state.data as ScenarioRun | undefined)?.status;
      if (status && TERMINAL_RUN_STATUSES.has(status)) return false;
      return streamState === 'live' ? false : 1000;
    },
  });

  useEffect(() => {
    setStreamState('connecting');
    const url = scenarioRunStreamUrl(runId);
    const unsubscribe = subscribeToStream(
      url,
      {
        onOpen: () => setStreamState('live'),
        onMessage: () => {
          setStreamState('live');
          queryClient.invalidateQueries({ queryKey: ['scenario-run', runId] });
        },
        onError: () => setStreamState((s) => (s === 'live' ? s : 'unavailable')),
      },
      ['timeline-entry', 'run-status'],
    );
    return unsubscribe;
  }, [runId, queryClient]);

  const elapsedLabel = (() => {
    if (!data) return null;
    if (data.elapsedMs != null) return `${(data.elapsedMs / 1000).toFixed(2)}s`;
    if (data.status === 'RUNNING') {
      const ms = Date.now() - new Date(data.startedAt).getTime();
      return `${(ms / 1000).toFixed(1)}s (running)`;
    }
    return null;
  })();

  return (
    <section>
      <div className="page-header">
        <h1>Scenario run {runId}</h1>
        <button onClick={onBack}>Back to scenarios</button>
      </div>

      <div className={`stream-indicator stream-${streamState}`}>
        {streamState === 'live' && 'Live — updates via SSE'}
        {streamState === 'connecting' && 'Connecting to live updates…'}
        {streamState === 'unavailable' && 'Live stream unavailable — falling back to polling'}
      </div>

      {isLoading && <p>Loading run…</p>}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && (
        <>
          <div className="order-summary-card">
            <h2>{data.scenarioName}</h2>
            <dl>
              <dt>Status</dt>
              <dd>
                <span className={`status ${data.status === 'COMPLETED' ? 'status-success' : data.status === 'FAILED' ? 'status-failure' : 'status-pending'}`}>
                  {data.status}
                </span>
              </dd>
              {elapsedLabel && (
                <>
                  <dt>Elapsed</dt>
                  <dd>{elapsedLabel}</dd>
                </>
              )}
              <dt>Correlation ID</dt>
              <dd>{data.correlationId}</dd>
              {data.orderId && (
                <>
                  <dt>Order</dt>
                  <dd>
                    <Link to={`/orders/${data.orderId}`}>{data.orderId}</Link>
                  </dd>
                </>
              )}
              {data.errorMessage && (
                <>
                  <dt>Error</dt>
                  <dd className="error">{data.errorMessage}</dd>
                </>
              )}
            </dl>
          </div>

          <h3>Timeline</h3>
          {data.timeline.length === 0 && <p>No timeline entries yet.</p>}
          {data.timeline.length > 0 && (
            <ol className="timeline">
              {data.timeline.map((entry) => (
                <TimelineEntryDetail key={entry.sequence} entry={entry} />
              ))}
            </ol>
          )}
        </>
      )}
    </section>
  );
}
