import type { OrderStatus } from '../api/orders';

// docs/order-state-machine.md (lines 21-26, 119-124) draws an explicit line between order
// terminal states: REJECTED_OUT_OF_STOCK and PAYMENT_FAILED are "expected outcomes" — legitimate
// business rejections the domain design intends some orders to reach. FAILED is different in
// kind — the doc calls it "a fault," reachable only when an event could not be processed. Folding
// all three into one alarming red badge hides that distinction from a viewer; this keeps it.
const EXPECTED_OUTCOME_STATUSES: ReadonlySet<OrderStatus> = new Set([
  'REJECTED_OUT_OF_STOCK',
  'PAYMENT_FAILED',
]);

const STATUS_CLASS: Record<OrderStatus, string> = {
  PENDING: 'status status-pending',
  INVENTORY_RESERVED: 'status status-pending',
  PAYMENT_PENDING: 'status status-pending',
  PAID: 'status status-pending',
  FULFILLMENT_PENDING: 'status status-pending',
  FULFILLED: 'status status-success',
  REJECTED_OUT_OF_STOCK: 'status status-expected',
  PAYMENT_FAILED: 'status status-expected',
  FAILED: 'status status-failure',
};

const STATUS_TITLE: Partial<Record<OrderStatus, string>> = {
  REJECTED_OUT_OF_STOCK:
    'Expected business rejection, not a system fault — the domain design intends some orders to end here.',
  PAYMENT_FAILED:
    'Expected business rejection, not a system fault — the domain design intends some orders to end here.',
  FAILED: "A genuine processing fault — one of this order's events could not be processed.",
};

export function StatusBadge({ status }: { status: OrderStatus }) {
  const isExpectedOutcome = EXPECTED_OUTCOME_STATUSES.has(status);
  return (
    <span className={STATUS_CLASS[status]} title={STATUS_TITLE[status]}>
      {isExpectedOutcome && <span aria-hidden="true">&#9432; </span>}
      {status.replaceAll('_', ' ')}
    </span>
  );
}
