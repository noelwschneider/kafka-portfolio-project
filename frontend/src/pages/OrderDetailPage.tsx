import { useQuery } from '@tanstack/react-query';
import { getOrder } from '../api/orders';
import { StatusBadge } from '../components/StatusBadge';

interface Props {
  orderId: string;
  onBack: () => void;
}

export function OrderDetailPage({ orderId, onBack }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => getOrder(orderId),
    // the whole workflow runs synchronously this phase (no Kafka yet), so the order is already in
    // its final state by the time this page loads — a short poll just covers a page left open
    // across a manual PUT /demo/payment-behavior + retry during a demo.
    refetchInterval: 4000,
  });

  return (
    <section>
      <div className="page-header">
        <h1>Order detail</h1>
        <button onClick={onBack}>Back to orders</button>
      </div>

      {isLoading && <p>Loading order…</p>}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && (
        <div className="order-detail">
          <div className="order-summary-card">
            <h2>{data.id}</h2>
            <StatusBadge status={data.status} />
            <dl>
              <dt>Customer</dt>
              <dd>{data.customerId}</dd>
              <dt>Total</dt>
              <dd>${data.totalAmount.toFixed(2)}</dd>
              <dt>Created</dt>
              <dd>{new Date(data.createdAt).toLocaleString()}</dd>
              <dt>Updated</dt>
              <dd>{new Date(data.updatedAt).toLocaleString()}</dd>
            </dl>
          </div>

          <h3>Items</h3>
          <table>
            <thead>
              <tr>
                <th>SKU</th>
                <th>Quantity</th>
                <th>Unit price</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.sku}>
                  <td>{item.sku}</td>
                  <td>{item.quantity}</td>
                  <td>${item.unitPrice.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3>Status history</h3>
          <ol className="status-history">
            {data.statusHistory.map((entry, index) => (
              <li key={index}>
                <StatusBadge status={entry.status} />
                <span>{new Date(entry.occurredAt).toLocaleString()}</span>
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  );
}
