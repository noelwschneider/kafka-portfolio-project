# 2.6 — The first frontend

[← Testing](5-testing.md) · [Chapter 2 ↑](README.md)

Three pages: create an order, list orders, view one order. That is the whole of Phase 1's frontend,
and it is deliberately small — the real console arrives in
[Chapter 5](../05-scenarios-and-frontend/README.md).

---

## What this frontend is for

Not a storefront. `docs/planning/project-overview.md` is explicit: *"Do not spend excessive time on
visual storefront polish."* At this stage the frontend has exactly one job — **prove the API is usable
by a real client**, which catches a category of problem that integration tests never will: a response
shape that is awkward to render, a missing field, an error body that cannot be displayed usefully.

## The stack

Vite, React 19, TypeScript, TanStack Query. Node 22. All pinned in
`docs/planning/project-overview.md` §0.

> **Primer — [React: components, state, and hooks](../technology/react/components-and-hooks.md)**
> The rendering model, `useState` and immutable updates, props and lifting state up, list keys,
> `useEffect` and cleanup, the rules of hooks, custom hooks.

> **Primer — [TanStack Query](../technology/react/tanstack-query.md)**
> Why server state is not client state, query keys and the cache, mutations and `invalidateQueries`,
> `staleTime` vs `gcTime`, polling vs pushing, and error handling.

**Vite** is the build tool and dev server. Two things it gives you that matter here: hot module
replacement fast enough that you stop thinking about it, and `import.meta.env` for build-time
configuration — the frontend equivalent of the `${VAR:default}` discipline from
[section 1](1-project-skeleton.md):

```ts
export const ORDER_SERVICE_BASE_URL = import.meta.env.VITE_ORDER_SERVICE_URL ?? 'http://localhost:8081';
```

Variables must be prefixed `VITE_` to be exposed to client code — a deliberate guard, since anything
exposed here ends up in the shipped bundle and is public. Never put a secret behind `VITE_`.

---

## The API layer

One thin module wraps `fetch`, and everything goes through it.

```ts
export async function apiFetch<T>(baseUrl: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body = await response.json();
  if (!response.ok) {
    throw new ApiRequestError(body as ApiError);
  }
  return body as T;
}
```

Three decisions in fifteen lines.

**It throws on `!response.ok`.** `fetch` famously does *not* — a 400 or a 500 resolves normally with
`ok: false`. A wrapper that forgets this hands an error body to the success path as if it were data,
and the bug surfaces as `undefined` in the UI rather than as an error. Every HTTP client you write
should start here.

**It throws a typed error carrying the server's `ApiError` body:**

```ts
export class ApiRequestError extends Error {
  readonly apiError: ApiError;
  constructor(apiError: ApiError) {
    super(apiError.message);
    this.apiError = apiError;
  }
}
```

This is the payoff for the shared error envelope in [section 3](3-the-http-layer.md). The frontend
gets `code`, `message`, and `correlationId` as structured data, so it can display the server's own
message rather than "Request failed," and branch on `code` where it needs to:

```ts
const errorMessage =
  mutation.error instanceof ApiRequestError ? mutation.error.apiError.message : mutation.error?.message;
```

Server-supplied message when the server answered; the raw error when it did not — a network failure,
a CORS rejection, unparseable JSON.

**204 is handled explicitly**, because `response.json()` on an empty body throws a parse error that
looks nothing like the actual problem.

## Types mirror the contract

```ts
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

export interface OrderDetail extends OrderSummary {
  items: OrderItem[];
  statusHistory: OrderStatusHistoryEntry[];
}
```

Hand-written from the OpenAPI spec, with a comment naming the file they mirror.

The `OrderStatus` union is doing real work. A **string literal union** means TypeScript rejects a
typo at compile time and, more usefully, gives you **exhaustiveness checking**: a `switch` over
`OrderStatus` that forgets `FAILED` is a type error if you write it so the compiler can tell. That is
[Chapter 1](../01-design-contract/3-state-and-api-contracts.md)'s frozen enum, enforced on the client.

> **Worth flagging.** These types are *hand-written* from the spec, which means nothing detects
> divergence if the spec changes. Generating them from `docs/openapi/*.yaml` would close that gap.
> The project does not do this, and given a frozen contract and one frontend it is a defensible call —
> but it is the honest answer if anyone asks how the client stays in sync.

## Pages

Three, matching Phase 1's list: create, list, detail.

```tsx
export function OrdersListPage({ onSelectOrder, onCreateOrder }: Props) {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['orders'],
    queryFn: listOrders,
    refetchInterval: 4000,
  });

  return (
    <section>
      {isLoading && <p>Loading orders…</p>}
      {isError && <p className="error">{(error as Error).message}</p>}
      {data && data.content.length === 0 && <p>No orders yet.</p>}
      {data && data.content.length > 0 && (
        <table className="orders-table">
          <tbody>
            {data.content.map((order) => (
              <tr key={order.id} onClick={() => onSelectOrder(order.id)} className="order-row">
                <td>{order.id}</td>
                <td><StatusBadge status={order.status} /></td>
                <td>${order.totalAmount.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
```

**`refetchInterval: 4000` is the interesting line.** The frontend polls every four seconds, because an
order's status changes without the client doing anything — inventory, payment, and fulfillment all
move it — and there is currently no way to be told.

Polling is the honest answer *at this stage*. It is also visibly unsatisfying: four seconds of lag on
a workflow that completes in milliseconds, and a request every four seconds forever whether or not
anything changed.

> **Not yet.** [Chapter 5](../05-scenarios-and-frontend/README.md) replaces this with SSE
> (ADR-003) — the server pushes each status change as it happens. Feeling why polling is inadequate
> before reaching for the replacement is worth the detour; SSE is otherwise just an unexplained
> technology choice.

### Navigation, and what replaces it later

The pages take **callback props** rather than knowing about routing:

```tsx
interface Props {
  onSelectOrder: (orderId: string) => void;
  onCreateOrder: () => void;
}
```

At this stage the parent is a `useState` switch over three views. React Router arrives in Chapter 5,
and the real `App.tsx` records exactly why:

> Phase 5: seven pages replace the earlier state-based `view` switch in this file (list/create/detail
> only). A `useState` view switch does not scale to seven top-level pages plus nested [routes]
> (e.g. sharing a link straight to a scenario run) and back-button-navigable.

Note what the upgrade cost. Because the pages take callbacks and know nothing about URLs, adding the
router meant writing thin wrapper components and changing **nothing** inside the pages:

```tsx
function OrdersListRoute() {
  const navigate = useNavigate();
  return (
    <OrdersListPage
      onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)}
      onCreateOrder={() => navigate('/orders/new')}
    />
  );
}
```

That is the same principle as keeping business logic out of controllers, on the other side of the
wire: a component that reports *what happened* rather than deciding *what it means* survives a change
of what it means.

### The create form

```tsx
const mutation = useMutation({
  mutationFn: createOrder,
  onSuccess: (accepted) => {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    onOrderCreated(accepted.id);
  },
});
```

After a successful create, `invalidateQueries(['orders'])` marks the list stale and TanStack Query
refetches — no manual cache patching, no reimplementing the server's sort order client-side.

The SKU dropdown is populated from Inventory Service (`useQuery({ queryKey: ['inventory'] })`), which
is the frontend making the ADR-004 boundary visible: **product names come from one service, prices
from another, and the client assembles the view.** That is the "no cross-domain joins" cost from
[section 1 of Chapter 1](../01-design-contract/1-boundaries-and-ownership.md), paid at the only place
that can pay it.

---

## Chapter 2 in one paragraph

You now have a working order-fulfillment system: four domain packages with real business rules, real
PostgreSQL persistence under Flyway, a validated HTTP API with one shared error model, integration
tests against real infrastructure, and a small React client that exercises it. It runs in one process,
completes a whole order inside a single HTTP request, and knows nothing about Kafka. Everything from
here is about taking that apart.

---

[← Testing](5-testing.md) · [Chapter 2 ↑](README.md) · [Chapter 3 — Kafka, and the split into services →](../03-kafka-and-services/README.md)
