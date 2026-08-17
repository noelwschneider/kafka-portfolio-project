import { apiFetch } from './client';

// Mirrors docs/openapi/inventory-service.yaml's InventoryItem — used only to populate the SKU
// picker on the create-order form with real stock, not a fabricated catalog.
export interface InventoryItem {
  sku: string;
  displayName: string;
  availableQuantity: number;
  reservedQuantity: number;
  version: number;
  updatedAt: string;
}

export function listInventory(): Promise<InventoryItem[]> {
  return apiFetch<InventoryItem[]>('/api/inventory');
}
