import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listOrders, type OrderSummary } from '../api/orders';
import { StatusBadge } from '../components/StatusBadge';
import { LoadingHint } from '../components/LoadingHint';
import { CreateOrderPage } from './CreateOrderPage';

interface Props {
  onSelectOrder: (orderId: string) => void;
  onOrderCreated: (orderId: string) => void;
  // Lets `/orders/new` deep-link straight into the create panel (issue #7) without the panel
  // itself needing to be a route — App.tsx decides whether the panel should start open based on
  // the current path.
  initialCreateOpen?: boolean;
  onCreateClosed?: () => void;
}

type SortKey = 'id' | 'customerId' | 'status' | 'totalAmount' | 'createdAt';
type SortDir = 'asc' | 'desc';

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'id', label: 'Order' },
  { key: 'customerId', label: 'Customer' },
  { key: 'status', label: 'Status' },
  { key: 'totalAmount', label: 'Total' },
  { key: 'createdAt', label: 'Created' },
];

// Compact "Aug 26, 2:14 PM" formatting for the Created column — no year, no seconds.
const createdAtFormatter = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

const PAGE_SIZE = 20;

function sortOrders(orders: OrderSummary[], key: SortKey, dir: SortDir): OrderSummary[] {
  const sorted = [...orders].sort((a, b) => {
    const av = a[key];
    const bv = b[key];
    if (typeof av === 'number' && typeof bv === 'number') return av - bv;
    return String(av).localeCompare(String(bv));
  });
  return dir === 'asc' ? sorted : sorted.reverse();
}

export function OrdersListPage({ onSelectOrder, onOrderCreated, initialCreateOpen = false, onCreateClosed }: Props) {
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['orders', page],
    queryFn: () => listOrders({ page, size: PAGE_SIZE }),
    refetchInterval: 4000,
  });

  const [sortKey, setSortKey] = useState<SortKey>('createdAt');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [isCreateOpen, setCreateOpen] = useState(initialCreateOpen);
  // Tracks where the mousedown that led to a click landed, so the overlay only closes on a
  // genuine click-on-overlay (mousedown and mouseup/click target both on the overlay itself) —
  // not a text-selection drag that starts inside the modal and is released outside it (issue #24).
  const overlayMouseDownTarget = useRef<EventTarget | null>(null);

  // Keeps the panel in sync if the route changes under us (e.g. a deep link to /orders/new
  // arriving while OrdersListPage is already mounted).
  useEffect(() => {
    setCreateOpen(initialCreateOpen);
  }, [initialCreateOpen]);

  function closeCreate() {
    setCreateOpen(false);
    onCreateClosed?.();
  }

  function handleCreated(orderId: string) {
    setCreateOpen(false);
    onOrderCreated(orderId);
  }

  function toggleSort(key: SortKey) {
    if (key === sortKey) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir(key === 'createdAt' ? 'desc' : 'asc');
    }
  }

  const sortedOrders = useMemo(
    () => (data ? sortOrders(data.content, sortKey, sortDir) : []),
    [data, sortKey, sortDir],
  );

  return (
    <section>
      <div className="page-header">
        <h1>Orders</h1>
        <button onClick={() => setCreateOpen(true)}>New order</button>
      </div>

      {isLoading && <LoadingHint label="Loading orders…" />}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && data.content.length === 0 && (
        <div className="empty-state">
          <p>No orders yet.</p>
          <button onClick={() => setCreateOpen(true)}>Place the first order</button>
        </div>
      )}

      {data && data.content.length > 0 && (
        <table className="orders-table">
          <thead>
            <tr>
              {COLUMNS.map((col) => (
                <th key={col.key}>
                  <button
                    type="button"
                    className="sort-header"
                    onClick={() => toggleSort(col.key)}
                    aria-sort={sortKey === col.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
                  >
                    {col.label}
                    {sortKey === col.key && <span className="sort-arrow">{sortDir === 'asc' ? '▲' : '▼'}</span>}
                  </button>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sortedOrders.map((order) => (
              <tr key={order.id} onClick={() => onSelectOrder(order.id)} className="order-row">
                <td className="order-id-cell">{order.id}</td>
                <td>{order.customerId}</td>
                <td>
                  <StatusBadge status={order.status} />
                </td>
                <td className="order-total-cell">
                  <span className="order-total-value">
                    <span className="order-total-currency">$</span>
                    <span className="order-total-amount">{order.totalAmount.toFixed(2)}</span>
                  </span>
                </td>
                <td>{createdAtFormatter.format(new Date(order.createdAt))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {data && data.totalElements > 0 && (
        <div className="pagination">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={data.page <= 0}
          >
            Previous
          </button>
          <span className="pagination-status">
            Page {data.page + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} orders
          </span>
          <button
            type="button"
            onClick={() => setPage((p) => p + 1)}
            disabled={data.page + 1 >= data.totalPages}
          >
            Next
          </button>
        </div>
      )}

      {isCreateOpen && (
        <div
          className="modal-overlay"
          onMouseDown={(e) => {
            overlayMouseDownTarget.current = e.target;
          }}
          onClick={(e) => {
            if (e.target === e.currentTarget && overlayMouseDownTarget.current === e.currentTarget) {
              closeCreate();
            }
          }}
        >
          <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="New order">
            <div className="modal-header">
              <h2>New order</h2>
              <button type="button" className="modal-close" onClick={closeCreate} aria-label="Close">
                ×
              </button>
            </div>
            <CreateOrderPage onOrderCreated={handleCreated} onCancel={closeCreate} />
          </div>
        </div>
      )}
    </section>
  );
}
