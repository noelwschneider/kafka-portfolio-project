import type { OrderStatus } from '../api/orders';

const STATUS_CLASS: Record<OrderStatus, string> = {
  PENDING: 'status status-pending',
  INVENTORY_RESERVED: 'status status-pending',
  PAYMENT_PENDING: 'status status-pending',
  PAID: 'status status-pending',
  FULFILLMENT_PENDING: 'status status-pending',
  FULFILLED: 'status status-success',
  REJECTED_OUT_OF_STOCK: 'status status-failure',
  PAYMENT_FAILED: 'status status-failure',
  FAILED: 'status status-failure',
};

export function StatusBadge({ status }: { status: OrderStatus }) {
  return <span className={STATUS_CLASS[status]}>{status.replaceAll('_', ' ')}</span>;
}
