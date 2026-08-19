import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryEvents, type EventQueryFilters } from '../api/events';

// frontend-design.md §12.5. See src/api/events.ts's file header for the full status: the backing
// query endpoint is not yet defined in docs/openapi/scenario-service.yaml as of this build, so this
// page renders its filters and layout for real but shows an explicit "not yet wired" banner
// instead of a fabricated or silently-empty table.
export function EventExplorerPage() {
  const [filters, setFilters] = useState<EventQueryFilters>({});

  const { data } = useQuery({
    queryKey: ['events', filters],
    queryFn: () => queryEvents(filters),
  });

  return (
    <section>
      <div className="page-header">
        <h1>Event Explorer</h1>
      </div>

      <p className="hint">
        Recent domain events, filterable by type, order, correlation id, service, topic, and
        dead-lettered status. Backed by a lightweight event projection rather than querying Kafka
        directly (frontend-design.md §12.5).
      </p>

      <form className="event-filters" onSubmit={(e) => e.preventDefault()}>
        <label>
          Event type
          <input
            value={filters.eventType ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, eventType: e.target.value || undefined }))}
            placeholder="OrderCreated"
          />
        </label>
        <label>
          Order ID
          <input
            value={filters.orderId ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, orderId: e.target.value || undefined }))}
            placeholder="order-21873"
          />
        </label>
        <label>
          Correlation ID
          <input
            value={filters.correlationId ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, correlationId: e.target.value || undefined }))}
          />
        </label>
        <label>
          Service
          <input
            value={filters.service ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, service: e.target.value || undefined }))}
            placeholder="inventory-service"
          />
        </label>
        <label>
          Topic
          <input
            value={filters.topic ?? ''}
            onChange={(e) => setFilters((f) => ({ ...f, topic: e.target.value || undefined }))}
            placeholder="orders.events"
          />
        </label>
        <label>
          Dead-lettered
          <select
            value={filters.deadLettered === undefined ? '' : String(filters.deadLettered)}
            onChange={(e) =>
              setFilters((f) => ({
                ...f,
                deadLettered: e.target.value === '' ? undefined : e.target.value === 'true',
              }))
            }
          >
            <option value="">Any</option>
            <option value="true">Dead-lettered only</option>
            <option value="false">Not dead-lettered</option>
          </select>
        </label>
      </form>

      {data && !data.wired && (
        <div className="not-wired-banner">
          <strong>Not yet wired.</strong> {data.reason}
        </div>
      )}

      <table>
        <thead>
          <tr>
            <th>Occurred</th>
            <th>Event type</th>
            <th>Order</th>
            <th>Topic</th>
            <th>Correlation ID</th>
            <th>DLQ</th>
          </tr>
        </thead>
        <tbody>
          {data?.events.map((event) => (
            <tr key={event.eventId}>
              <td>{new Date(event.occurredAt).toLocaleString()}</td>
              <td>{event.eventType}</td>
              <td>{event.aggregateId}</td>
              <td>{event.topic}</td>
              <td>{event.correlationId}</td>
              <td>{event.deadLettered ? 'yes' : 'no'}</td>
            </tr>
          ))}
          {data && data.events.length === 0 && (
            <tr>
              <td colSpan={6} className="hint">
                {data.wired ? 'No events match these filters.' : 'No data — endpoint not wired yet.'}
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  );
}
