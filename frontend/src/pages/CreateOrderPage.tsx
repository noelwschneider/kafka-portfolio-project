import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createOrder, type CreateOrderItem } from '../api/orders';
import { listInventory } from '../api/inventory';
import { ApiRequestError } from '../api/client';

interface Props {
  onOrderCreated: (orderId: string) => void;
  onCancel: () => void;
}

interface LineDraft {
  sku: string;
  quantity: number;
}

export function CreateOrderPage({ onOrderCreated, onCancel }: Props) {
  const queryClient = useQueryClient();
  const { data: inventory } = useQuery({ queryKey: ['inventory'], queryFn: listInventory });

  const [customerId, setCustomerId] = useState('demo-customer');
  const [lines, setLines] = useState<LineDraft[]>([{ sku: '', quantity: 1 }]);

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

  function addLine() {
    setLines((prev) => [...prev, { sku: '', quantity: 1 }]);
  }

  function removeLine(index: number) {
    setLines((prev) => prev.filter((_, i) => i !== index));
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    const items: CreateOrderItem[] = lines
      .filter((line) => line.sku)
      .map((line) => ({ sku: line.sku, quantity: line.quantity }));
    mutation.mutate({ customerId, items });
  }

  const errorMessage =
    mutation.error instanceof ApiRequestError ? mutation.error.apiError.message : mutation.error?.message;

  return (
    <section>
      <div className="page-header">
        <h1>New order</h1>
        <button onClick={onCancel}>Back to orders</button>
      </div>

      <form onSubmit={handleSubmit} className="order-form">
        <label>
          Customer id
          <input value={customerId} onChange={(e) => setCustomerId(e.target.value)} required />
        </label>

        <div className="line-items">
          {lines.map((line, index) => (
            <div className="line-item" key={index}>
              <select
                value={line.sku}
                onChange={(e) => updateLine(index, { sku: e.target.value })}
                required
              >
                <option value="" disabled>
                  Select SKU
                </option>
                {inventory?.map((item) => (
                  <option key={item.sku} value={item.sku}>
                    {item.sku} — {item.displayName} ({item.availableQuantity - item.reservedQuantity} free)
                  </option>
                ))}
              </select>
              <input
                type="number"
                min={1}
                max={100}
                value={line.quantity}
                onChange={(e) => updateLine(index, { quantity: Number(e.target.value) })}
                required
              />
              {lines.length > 1 && (
                <button type="button" onClick={() => removeLine(index)}>
                  Remove
                </button>
              )}
            </div>
          ))}
          <button type="button" onClick={addLine}>
            Add item
          </button>
        </div>

        {errorMessage && <p className="error">{errorMessage}</p>}

        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Placing order…' : 'Place order'}
        </button>
      </form>
    </section>
  );
}
