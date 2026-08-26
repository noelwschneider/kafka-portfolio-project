import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createOrder, listPrices, type CreateOrderItem } from '../api/orders';
import { listInventory } from '../api/inventory';
import { ApiRequestError } from '../api/client';
import { LoadingHint } from '../components/LoadingHint';

interface Props {
  onOrderCreated: (orderId: string) => void;
  onCancel: () => void;
}

interface LineDraft {
  sku: string;
  // '' represents the field being genuinely empty while the user is editing it — coercing to 0
  // made the input briefly show 0 whenever it was cleared (issue #24).
  quantity: number | '';
}

export function CreateOrderPage({ onOrderCreated, onCancel }: Props) {
  const queryClient = useQueryClient();
  const { data: inventory, isLoading: inventoryLoading } = useQuery({
    queryKey: ['inventory'],
    queryFn: listInventory,
  });
  // Read-only price lookup (issue #32) — GET /api/prices, Order Service's own seeded SKU price
  // map exposed for display only. Not used to price the order itself: that still happens
  // server-side at order creation from the same catalog.
  const { data: prices } = useQuery({
    queryKey: ['prices'],
    queryFn: listPrices,
  });
  const priceBySku = new Map((prices ?? []).map((p) => [p.sku, p.unitPrice]));

  const [customerId, setCustomerId] = useState('demo-customer');
  // Order lines the customer has actually picked, added one at a time from the inventory table
  // below (issue #22) — no placeholder empty line, since there's no dropdown left to leave blank.
  const [lines, setLines] = useState<LineDraft[]>([]);
  // Indexes of lines whose quantity failed submit-time validation (quantity cleared to empty)
  // so the field can be highlighted with an inline message instead of the input silently
  // coercing to 0.
  const [invalidQuantityLines, setInvalidQuantityLines] = useState<Set<number>>(new Set());
  // Submit-time validation for having picked nothing at all — distinct from the per-line
  // quantity message above.
  const [noLinesError, setNoLinesError] = useState(false);

  const mutation = useMutation({
    mutationFn: createOrder,
    onSuccess: (accepted) => {
      queryClient.invalidateQueries({ queryKey: ['orders'] });
      onOrderCreated(accepted.id);
    },
  });

  function updateLine(index: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  }

  function addSkuToOrder(sku: string) {
    setNoLinesError(false);
    setLines((prev) => {
      const existingIndex = prev.findIndex((line) => line.sku === sku);
      if (existingIndex === -1) {
        return [...prev, { sku, quantity: 1 }];
      }
      return prev.map((line, i) =>
        i === existingIndex ? { ...line, quantity: (line.quantity === '' ? 0 : line.quantity) + 1 } : line,
      );
    });
  }

  function removeLine(index: number) {
    setLines((prev) => prev.filter((_, i) => i !== index));
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();

    if (lines.length === 0) {
      setNoLinesError(true);
      return;
    }
    setNoLinesError(false);

    const invalid = new Set<number>();
    lines.forEach((line, index) => {
      if (line.quantity === '' || line.quantity < 1) {
        invalid.add(index);
      }
    });
    if (invalid.size > 0) {
      setInvalidQuantityLines(invalid);
      return;
    }
    setInvalidQuantityLines(new Set());

    const items: CreateOrderItem[] = lines.map((line) => ({ sku: line.sku, quantity: line.quantity as number }));
    mutation.mutate({ customerId, items });
  }

  const errorMessage =
    mutation.error instanceof ApiRequestError ? mutation.error.apiError.message : mutation.error?.message;

  // Rendered inline as a modal panel from OrdersListPage (issue #7) rather than as a standalone
  // routed page — no <section>/page-header chrome here; the modal wrapper supplies the title and
  // close affordance, this component owns only the form itself.
  return (
    <>
      <form onSubmit={handleSubmit} className="order-form">
        <label>
          Customer id
          <input value={customerId} onChange={(e) => setCustomerId(e.target.value)} required />
        </label>

        {inventoryLoading && <LoadingHint label="Loading inventory…" />}

        {inventory && inventory.length > 0 && (
          // Real inventory presented as a scannable table (issue #22) rather than dropdown option
          // text. Price column (issue #32) reads GET /api/prices — Order Service's own seeded SKU
          // price map, the same one it applies to OrderItem.unitPrice at order-creation time — not
          // Inventory Service, which carries no price field.
          <table className="inventory-table">
            <thead>
              <tr>
                <th>Product</th>
                <th>SKU</th>
                <th>Price</th>
                <th>Available</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {inventory.map((item) => {
                const free = item.availableQuantity - item.reservedQuantity;
                const price = priceBySku.get(item.sku);
                return (
                  <tr key={item.sku}>
                    <td>{item.displayName}</td>
                    <td className="order-id-cell">{item.sku}</td>
                    <td>{price !== undefined ? `$${price.toFixed(2)}` : '—'}</td>
                    <td>{free}</td>
                    <td>
                      <button type="button" onClick={() => addSkuToOrder(item.sku)} disabled={free <= 0}>
                        Add
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        <div className="line-items">
          {lines.length === 0 && <p className="hint">No items added yet — add one from the table above.</p>}
          {lines.map((line, index) => {
            const item = inventory?.find((i) => i.sku === line.sku);
            return (
              <div className="line-item" key={line.sku}>
                <span className="line-item-name">{item ? item.displayName : line.sku}</span>
                <input
                  type="number"
                  min={1}
                  max={100}
                  value={line.quantity}
                  onChange={(e) => {
                    const raw = e.target.value;
                    updateLine(index, { quantity: raw === '' ? '' : Number(raw) });
                    if (invalidQuantityLines.has(index)) {
                      setInvalidQuantityLines((prev) => {
                        const next = new Set(prev);
                        next.delete(index);
                        return next;
                      });
                    }
                  }}
                  className={invalidQuantityLines.has(index) ? 'input-invalid' : undefined}
                  aria-invalid={invalidQuantityLines.has(index)}
                />
                {invalidQuantityLines.has(index) && <span className="field-error">Enter a quantity</span>}
                <button type="button" onClick={() => removeLine(index)}>
                  Remove
                </button>
              </div>
            );
          })}
        </div>

        {noLinesError && <p className="error">Add at least one item.</p>}
        {errorMessage && <p className="error">{errorMessage}</p>}

        <div className="order-form-actions">
          <button type="button" onClick={onCancel} className="button-secondary">
            Cancel
          </button>
          <button type="submit" className="button-primary" disabled={mutation.isPending}>
            {mutation.isPending ? 'Placing order…' : 'Place order'}
          </button>
        </div>
      </form>
    </>
  );
}
