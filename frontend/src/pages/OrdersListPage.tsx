import { useQuery } from '@tanstack/react-query';
import { listOrders } from '../api/orders';
import { StatusBadge } from '../components/StatusBadge';

interface Props {
  onSelectOrder: (orderId: string) => void;
  onCreateOrder: () => void;
}

export function OrdersListPage({ onSelectOrder, onCreateOrder }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['orders'],
    queryFn: listOrders,
    refetchInterval: 4000,
  });

  return (
    <section>
      <div className="page-header">
        <h1>Orders</h1>
        <button onClick={onCreateOrder}>New order</button>
      </div>

      {isLoading && <p>Loading orders…</p>}
      {isError && <p className="error">{(error as Error).message}</p>}

      {data && data.content.length === 0 && <p>No orders yet.</p>}

      {data && data.content.length > 0 && (
        <table className="orders-table">
          <thead>
            <tr>
              <th>Order</th>
              <th>Customer</th>
              <th>Status</th>
              <th>Total</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {data.content.map((order) => (
              <tr key={order.id} onClick={() => onSelectOrder(order.id)} className="order-row">
                <td>{order.id}</td>
                <td>{order.customerId}</td>
                <td>
                  <StatusBadge status={order.status} />
                </td>
                <td>${order.totalAmount.toFixed(2)}</td>
                <td>{new Date(order.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
