# TanStack Query

*Referenced from [Chapter 2.6 — The first frontend](../../02-domain/6-the-first-frontend.md).*

---

## Server state is not client state

The insight the library is built on: **data that lives on a server is a fundamentally different thing
from data that lives in your component**, and treating them the same is why so much frontend code is
tangled.

| Client state | Server state |
|---|---|
| You own it | Someone else owns it |
| Always current | A cached copy that may already be stale |
| Synchronous | Asynchronous, and can fail |
| Only you change it | Changes without telling you |

Putting server state in `useState` + `useEffect` means hand-writing, per endpoint: loading flags,
error flags, caching, deduplication of concurrent requests, refetching, invalidation after writes, and
cleanup of responses that arrive after the component unmounted or after a newer request already
resolved. That last one is a race condition most hand-rolled implementations get wrong.

## Queries

```tsx
const { data, isLoading, isError, error } = useQuery({
  queryKey: ['orders'],
  queryFn: listOrders,
  refetchInterval: 4000,
});
```

- **`queryKey`** — the cache identity. Two components using `['orders']` share one cache entry and one
  in-flight request. The key is also what invalidation targets.
- **`queryFn`** — any function returning a promise. It knows nothing about React; it can be a plain
  `fetch` wrapper.
- The return gives you loading, error, and success states as data rather than as branches you
  maintain.

**Keys are hierarchical arrays**, and this is the part worth designing deliberately:

```tsx
['orders']                  // the list
['orders', orderId]         // one order
['orders', { status }]      // a filtered list
```

Invalidating `['orders']` invalidates everything beneath it. Getting the hierarchy right up front
makes cache management nearly free later.

## Mutations

```tsx
const queryClient = useQueryClient();

const mutation = useMutation({
  mutationFn: createOrder,
  onSuccess: (accepted) => {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    onOrderCreated(accepted.id);
  },
});

mutation.mutate({ customerId, items });
```

Mutations are for writes. They are not cached and are triggered imperatively rather than on render.

**`invalidateQueries` is the core idea.** Rather than manually patching the cache after a write, you
mark the affected keys stale and let the library refetch. The server stays the source of truth, and
you never have to reimplement its logic client-side to predict what the new list looks like.

`mutation.isPending`, `mutation.error`, and `mutation.isSuccess` cover the UI states, so a submit
button's disabled state is derived rather than tracked by hand.

## Freshness

Two settings, and the distinction between them matters:

- **`staleTime`** — how long data is considered fresh. While fresh, remounting a component uses the
  cache with **no** network request. Default `0`: everything is stale immediately.
- **`gcTime`** — how long an *unused* cache entry is kept before being discarded. Default 5 minutes.

Refetches happen on window focus, on reconnect, on remount, and on `refetchInterval` — all
configurable, globally or per query.

```tsx
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 2000, retry: 1 } },
});
```

## Polling, and when to stop

`refetchInterval` polls. It is the right tool when the data changes without you and you have no push
channel — and the wrong tool when you *do*.

For a resource that changes rapidly and pushes updates (a live status stream), the better pattern is a
push subscription that writes into the cache directly:

```tsx
queryClient.setQueryData(['orders', orderId], (old) => ({ ...old, status: newStatus }));
```

or an `invalidateQueries` call from the push handler, which refetches once rather than every N seconds
forever. A poll left in place next to a working stream is a common and easy-to-miss waste.

## Errors

`queryFn` communicates failure by **throwing**. That is worth stating because `fetch` does not throw
on a 4xx or 5xx — it resolves with `ok: false`. A wrapper that does not check `response.ok` will hand
an error body to your success path as if it were data.

Throwing a typed error subclass lets components discriminate:

```tsx
const message = mutation.error instanceof ApiRequestError
  ? mutation.error.apiError.message     // the server's own message
  : mutation.error?.message;            // network failure, parse failure
```

`retry` defaults to 3 attempts with exponential backoff — usually right for reads, and usually wrong
for writes, which is why mutations default to no retries.
