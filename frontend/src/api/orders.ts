import { apiFetch } from './client';

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

export function createOrder(request: CreateOrderRequest): Promise<OrderAccepted> {
  return apiFetch<OrderAccepted>('/api/orders', {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function listOrders(): Promise<OrderPage> {
  return apiFetch<OrderPage>('/api/orders?size=50');
}

export function getOrder(orderId: string): Promise<OrderDetail> {
  return apiFetch<OrderDetail>(`/api/orders/${orderId}`);
}
