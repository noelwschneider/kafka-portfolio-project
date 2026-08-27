import { apiFetch, ORDER_SERVICE_BASE_URL } from './client';

// Mirrors docs/openapi/order-service.yaml — the Order Service's frozen contract.

export type OrderStatus =
  | 'PENDING'
  | 'INVENTORY_RESERVED'
  | 'REJECTED_OUT_OF_STOCK'
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PAYMENT_FAILED'
  | 'FULFILLMENT_PENDING'
  | 'FULFILLED'
  | 'FAILED';

export interface CreateOrderItem {
  sku: string;
  quantity: number;
}

export interface CreateOrderRequest {
  customerId: string;
  items: CreateOrderItem[];
}

export interface OrderAccepted {
  id: string;
  status: OrderStatus;
  createdAt: string;
}

export interface OrderSummary {
  id: string;
  customerId: string;
  status: OrderStatus;
  totalAmount: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrderItem {
  sku: string;
  quantity: number;
  unitPrice: number;
}

export interface OrderStatusHistoryEntry {
  status: OrderStatus;
  sourceEventId: string | null;
  occurredAt: string;
}

export interface OrderDetail extends OrderSummary {
  items: OrderItem[];
  statusHistory: OrderStatusHistoryEntry[];
}

export interface OrderPage {
  content: OrderSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SkuPrice {
  sku: string;
  unitPrice: number;
}

export function createOrder(request: CreateOrderRequest): Promise<OrderAccepted> {
  return apiFetch<OrderAccepted>(ORDER_SERVICE_BASE_URL, '/api/orders', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export interface ListOrdersParams {
  page?: number;
  size?: number;
  status?: OrderStatus;
  customerId?: string;
}

export function listOrders({ page = 0, size = 20, status, customerId }: ListOrdersParams = {}): Promise<OrderPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) params.set('status', status);
  if (customerId) params.set('customerId', customerId);
  return apiFetch<OrderPage>(ORDER_SERVICE_BASE_URL, `/api/orders?${params.toString()}`);
}

export function getOrder(orderId: string): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(ORDER_SERVICE_BASE_URL, `/api/orders/${orderId}`);
}

// GET /api/prices (issue #32) — read-only exposure of the same seeded SKU price map Order Service
// applies to OrderItem.unitPrice at order creation. Used to show a price on the New Order form
// before an item is added; never used to price an order client-side.
export function listPrices(): Promise<SkuPrice[]> {
  return apiFetch<SkuPrice[]>(ORDER_SERVICE_BASE_URL, '/api/prices');
}
