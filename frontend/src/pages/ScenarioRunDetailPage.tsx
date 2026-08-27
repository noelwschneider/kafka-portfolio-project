import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  getScenarioRun,
  listScenarios,
  scenarioRunStreamUrl,
  type ScenarioRun,
  type ScenarioRunStatus,
  type ScenarioTimelineEntry,
} from '../api/scenarios';
import { getOrder } from '../api/orders';
import { subscribeToStream } from '../api/client';
import { narrateTimelineEntry } from '../lib/scenarioNarrative';
import { LoadingHint } from '../components/LoadingHint';

interface Props {
  runId: string;
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

function TimelineEntryDetail({
  entry,
  revealed,
  demonstrates,
}: {
  entry: ScenarioTimelineEntry;
  revealed: boolean;
  demonstrates: string[];
}) {
  const detail = entry.detail;
  const hasDetail = detail && Object.keys(detail).length > 0;
  const { title, headline } = narrateTimelineEntry(entry);

  const knownKeys = new Set(KNOWN_DETAIL_FIELDS.map((f) => f.key));
  const extraKeys = detail ? Object.keys(detail).filter((k) => !knownKeys.has(k)) : [];

  return (
    <li className={`timeline-entry timeline-${entry.kind.toLowerCase()} timeline-reveal${revealed ? ' timeline-revealed' : ''}`}>
      <div className="timeline-row">
        <span className="timeline-time">{new Date(entry.occurredAt).toLocaleTimeString(undefined, { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}.{new Date(entry.occurredAt).getMilliseconds().toString().padStart(3, '0')}</span>
        <div className="timeline-main">
          <span className="timeline-title">{title}</span>
          <span className="timeline-headline">{headline}</span>
          <span className="timeline-raw">
            <span className="timeline-kind">{entry.kind}</span>
            <span className="timeline-label">{entry.label}</span>
          </span>
          {demonstrates.length > 0 && (
            <ul className="timeline-demonstrates">
              {demonstrates.map((point) => (
                <li key={point}>{point}</li>
              ))}
            </ul>
          )}
        </div>
      </div>
      {hasDetail && (
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

// Heuristic map from a scenario definition's `demonstrates` phrase (the fixed vocabulary produced
// by ScenarioCatalog.java across all 8 scenarios) to the timeline entry it's actually evidenced by,
// so the point can render inline on that entry's card instead of as a disconnected list read before
// any of the actual data. Phrases that describe the run as a whole rather than one specific step
// (e.g. "asynchronous workflow", "consumer groups") intentionally have no matcher and fall back to a
// framing note — forcing every point onto one timeline entry would misrepresent what that entry
// actually shows.
function findEntry(
  timeline: ScenarioTimelineEntry[],
  predicate: (entry: ScenarioTimelineEntry) => boolean,
): number | null {
  const idx = timeline.findIndex(predicate);
  return idx === -1 ? null : idx;
}

const DEMONSTRATES_MATCHERS: Record<string, (timeline: ScenarioTimelineEntry[]) => number | null> = {
  'REST request': (t) => findEntry(t, (e) => e.kind === 'HTTP' && e.label.startsWith('POST /api/orders')),
  persistence: (t) =>
    findEntry(t, (e) => e.kind === 'EVENT' && e.label === 'OrderCreated' && e.detail?.phase === 'published'),
  'event publication': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.detail?.phase === 'published'),
  'Kafka consumption': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.detail?.phase === 'consumed'),
  'state transitions': (t) => findEntry(t, (e) => e.kind === 'STATE_CHANGE'),
  'domain validation': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.label === 'InventoryReservationFailed'),
  'inventory ownership': (t) =>
    findEntry(t, (e) => e.kind === 'EVENT' && (e.label === 'InventoryReserved' || e.label === 'InventoryReservationFailed')),
  'rejection events': (t) =>
    findEntry(t, (e) => e.kind === 'EVENT' && (e.label === 'InventoryReservationFailed' || e.label === 'PaymentRejected')),
  'downstream business failure': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.label === 'PaymentRejected'),
  compensation: (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.label === 'InventoryReleased'),
  'inventory release': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.label === 'InventoryReleased'),
  'eventual state correction': (t) => {
    const idx = [...t].reverse().findIndex((e) => e.kind === 'STATE_CHANGE');
    return idx === -1 ? null : t.length - 1 - idx;
  },
  'event IDs': (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.detail?.eventId != null),
  'idempotent consumers': (t) => {
    const seen = new Set<string>();
    for (let i = 0; i < t.length; i++) {
      const e = t[i];
      if (e.kind === 'EVENT' && e.detail?.phase === 'consumed') {
        if (seen.has(e.label)) return i;
        seen.add(e.label);
      }
    }
    return null;
  },
  'duplicate detection': (t) => DEMONSTRATES_MATCHERS['idempotent consumers'](t),
  offsets: (t) => findEntry(t, (e) => e.kind === 'EVENT' && e.detail?.offset != null),
  'consumer recovery': (t) => findEntry(t, (e) => e.kind === 'HTTP' && e.label.endsWith('/resume')),
  'retry policy': (t) => findEntry(t, (e) => e.detail?.retryCount != null),
  'dead-letter routing': (t) =>
    findEntry(t, (e) => e.detail?.error != null || (e.kind === 'STATE_CHANGE' && e.label.endsWith('FAILED'))),
  'event throughput': (t) => findEntry(t, (e) => e.kind === 'STATE_CHANGE' && e.label === 'High-volume batch summary'),
};

function matchDemonstratesPoint(point: string, timeline: ScenarioTimelineEntry[]): number | null {
  const matcher = DEMONSTRATES_MATCHERS[point];
  return matcher ? matcher(timeline) : null;
}

// Staged-reveal tuning: how far apart entries fade in, and the cap on how many entries get an
// individual stagger step before the remainder reveal together — so a long timeline (e.g. the
// high-volume scenario) doesn't take unreasonably long to finish appearing.
const REVEAL_STEP_MS = 180;
const REVEAL_STAGGER_CAP = 12;

export function ScenarioRunDetailPage({ runId }: Props) {
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

  const { data: scenarios, isLoading: scenariosLoading } = useQuery({
    queryKey: ['scenarios'],
    queryFn: listScenarios,
  });
  const scenarioDefinition = scenarios?.find((s) => s.name === data?.scenarioName);

  // The run's own status (RUNNING/COMPLETED/FAILED — did the scenario execute) is a distinct
  // concept from the order's business outcome (PENDING/REJECTED_OUT_OF_STOCK/etc.), fetched
  // separately. Only fetch once the run has actually reached a terminal state — otherwise the
  // order's status is still mid-flight and comparing it to "expected terminal" would be
  // meaningless noise, not a real mismatch.
  const runIsTerminal = data ? TERMINAL_RUN_STATUSES.has(data.status) : false;
  const { data: order } = useQuery({
    queryKey: ['scenario-run-order', data?.orderId],
    queryFn: () => getOrder(data!.orderId!),
    enabled: Boolean(data?.orderId) && runIsTerminal,
  });

  // Staged reveal: track how many of the current timeline entries are "revealed" and advance the
  // count on a timer chain. Runs on the client from a "nothing shown yet" state every time the
  // entry count grows, which covers both a genuinely-live incremental update and a run that was
  // already COMPLETED on the very first fetch (the common case, since this page usually mounts
  // after the run has finished).
  const [revealedCount, setRevealedCount] = useState(0);
  const revealedCountRef = useRef(0);
  const timelineLength = data?.timeline.length ?? 0;

  useEffect(() => {
    revealedCountRef.current = revealedCount;
  }, [revealedCount]);

  useEffect(() => {
    if (timelineLength <= revealedCountRef.current) return;
    let cancelled = false;
    const revealNext = () => {
      if (cancelled) return;
      setRevealedCount((current) => {
        if (current >= timelineLength) return current;
        const next = current + 1;
        if (next < timelineLength && next < REVEAL_STAGGER_CAP) {
          setTimeout(revealNext, REVEAL_STEP_MS);
        } else if (next < timelineLength) {
          // Past the stagger cap: reveal the remainder together rather than one-by-one.
          setTimeout(() => !cancelled && setRevealedCount(timelineLength), REVEAL_STEP_MS);
        }
        return next;
      });
    };
    setTimeout(revealNext, REVEAL_STEP_MS);
    return () => {
      cancelled = true;
    };
    // Re-run whenever the timeline grows (new fetch/new entries), not on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timelineLength]);

  useEffect(() => {
    setRevealedCount(0);
    revealedCountRef.current = 0;
  }, [runId]);

  // Apply each SSE message to the cached run directly instead of refetching the whole run document
  // per message. The stream already carries the full ScenarioTimelineEntry / run-status payload, so a
  // refetch asks the server to re-send everything already held plus one new entry.
  //
  // That distinction only matters at Scenario 8's scale, which is why it survived every other
  // scenario: a high-volume run emits ~520 timeline entries in a few seconds, and one refetch per
  // entry is O(n^2) — ~520 requests for a document growing to ~140KB, i.e. tens of MB of JSON parsed
  // and a ~520-item list re-rendered ~520 times, which is what froze the page live on the demo box.
  // A ten-entry scenario emits ten small refetches and looks fine either way.
  useEffect(() => {
    setStreamState('connecting');
    const url = scenarioRunStreamUrl(runId);
    const unsubscribe = subscribeToStream(
      url,
      {
        onOpen: () => setStreamState('live'),
        onMessage: (eventName, data) => {
          setStreamState('live');
          let parsed: unknown;
          try {
            parsed = JSON.parse(data);
          } catch {
            // Malformed frame: fall back to the authoritative fetch rather than dropping the update.
            queryClient.invalidateQueries({ queryKey: ['scenario-run', runId] });
            return;
          }
          queryClient.setQueryData<ScenarioRun>(['scenario-run', runId], (current) => {
            if (!current) return current;
            if (eventName === 'timeline-entry') {
              const entry = parsed as ScenarioTimelineEntry;
              // The server assigns a unique, monotonic sequence per run; ignore a duplicate rather
              // than rendering the same entry twice if EventSource replays after a reconnect.
              if (current.timeline.some((e) => e.sequence === entry.sequence)) return current;
              return {
                ...current,
                timeline: [...current.timeline, entry].sort((a, b) => a.sequence - b.sequence),
              };
            }
            if (eventName === 'run-status') {
              const status = parsed as { status: ScenarioRunStatus; orderId: string; completedAt: string };
              return {
                ...current,
                status: status.status,
                // RunEventHub sends '' rather than null for these two when absent.
                orderId: status.orderId === '' ? current.orderId : status.orderId,
                completedAt: status.completedAt === '' ? current.completedAt : status.completedAt,
              };
            }
            return current;
          });
          if (eventName === 'run-status') {
            // The run is terminal: one authoritative fetch reconciles anything the stream missed
            // (elapsedMs and errorMessage are not carried on the run-status frame) — once, not per entry.
            queryClient.invalidateQueries({ queryKey: ['scenario-run', runId] });
          }
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

  // Display-only: strip the `run-` prefix so the heading reads "Scenario run #227" instead of
  // doubling the word "run" ("Scenario run run-227"). The raw runId prop, route, and API calls are
  // untouched — this only affects what's rendered in the <h1>.
  const displayRunId = runId.startsWith('run-') ? `#${runId.slice('run-'.length)}` : runId;

  // Attach each `demonstrates` point to the timeline entry it's actually evidenced by (see
  // DEMONSTRATES_MATCHERS above); points with no reliable per-entry match — because they describe
  // the whole run rather than one step — stay in `unmatchedDemonstrates` and render as a framing
  // note instead of being forced onto an entry that doesn't really show them.
  const demonstratesByEntrySequence = new Map<number, string[]>();
  const unmatchedDemonstrates: string[] = [];
  if (data && scenarioDefinition) {
    for (const point of scenarioDefinition.demonstrates) {
      const idx = matchDemonstratesPoint(point, data.timeline);
      if (idx == null) {
        unmatchedDemonstrates.push(point);
      } else {
        const sequence = data.timeline[idx].sequence;
        const existing = demonstratesByEntrySequence.get(sequence) ?? [];
        existing.push(point);
        demonstratesByEntrySequence.set(sequence, existing);
      }
    }
  }

  return (
    <section>
      <div className="page-header">
        <h1>Scenario run {displayRunId}</h1>
      </div>

      <div className={`stream-indicator stream-${streamState}`}>
        {streamState === 'live' && 'Live — updates via SSE'}
        {streamState === 'connecting' && 'Connecting to live updates…'}
        {streamState === 'unavailable' && 'Live stream unavailable — falling back to polling'}
      </div>

      {isLoading && <LoadingHint label="Loading run…" />}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && (
        <>
          <div className="order-summary-card">
            <h2>{scenarioDefinition?.title ?? data.scenarioName}</h2>
            {scenariosLoading && !scenarioDefinition && <LoadingHint label="Loading scenario details…" />}
            {scenarioDefinition && (
              <div className="scenario-context">
                <p className="scenario-context-description">{scenarioDefinition.description}</p>
                {unmatchedDemonstrates.length > 0 && (
                  <p className="scenario-context-demonstrates-framing">
                    Also demonstrates, across the whole run: {unmatchedDemonstrates.join(', ')}
                  </p>
                )}
                {scenarioDefinition.expectedTerminalStatus && (
                  <p className="scenario-context-expected">
                    Expected terminal status:{' '}
                    <span className="status status-expected">{scenarioDefinition.expectedTerminalStatus}</span>
                  </p>
                )}
                {scenarioDefinition.expectedTerminalStatus && runIsTerminal && data?.orderId && (
                  <p className="scenario-context-outcome">
                    {order ? (
                      order.status === scenarioDefinition.expectedTerminalStatus ? (
                        <span className="status status-success">
                          Actual outcome matches expected: {order.status.replaceAll('_', ' ')}
                        </span>
                      ) : (
                        <span className="status status-failure">
                          Actual outcome differs from expected — order is {order.status.replaceAll('_', ' ')}
                        </span>
                      )
                    ) : (
                      <span className="hint">Checking actual order outcome…</span>
                    )}
                  </p>
                )}
              </div>
            )}
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
              <dt title="The id used to trace this run's HTTP request, Kafka events, and order together across every service.">
                Correlation ID
              </dt>
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
              {data.timeline.map((entry, index) => (
                <TimelineEntryDetail
                  key={entry.sequence}
                  entry={entry}
                  revealed={index < revealedCount}
                  demonstrates={demonstratesByEntrySequence.get(entry.sequence) ?? []}
                />
              ))}
            </ol>
          )}
        </>
      )}
    </section>
  );
}
