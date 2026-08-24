# React: components, state, and hooks

*Referenced from [Chapter 2.6 — The first frontend](../../02-domain/6-the-first-frontend.md).*

---

## The model

A React component is a function that takes **props** and returns a description of UI. React calls it,
compares the result against what is currently on screen, and applies the minimal DOM changes.

```tsx
interface Props {
  onSelectOrder: (orderId: string) => void;
}

export function OrdersListPage({ onSelectOrder }: Props) {
  return <button onClick={() => onSelectOrder('order-1')}>Open</button>;
}
```

The mental model that matters: **you describe what the UI should look like for a given state, and
React works out the DOM operations.** You never write "find that row and update its text."

**JSX** is syntax sugar for function calls — `<div className="x">hi</div>` compiles to
`React.createElement('div', { className: 'x' }, 'hi')`. Hence `className` rather than `class` (a
reserved word) and `htmlFor` rather than `for`.

**Rendering must be pure.** Given the same props and state, a component must return the same thing and
must not mutate anything outside itself. React may call it more than once per visible update — in
development, `StrictMode` deliberately double-invokes components to surface impure ones.

## State

`useState` gives a component a value that survives across renders, and a setter that triggers a
re-render:

```tsx
const [customerId, setCustomerId] = useState('demo-customer');
const [lines, setLines] = useState<LineDraft[]>([{ sku: '', quantity: 1 }]);
```

**Update immutably.** Never `lines.push(x)`. Create a new array or object:

```tsx
setLines((prev) => [...prev, { sku: '', quantity: 1 }]);
setLines((prev) => prev.filter((_, i) => i !== index));
setLines((prev) => prev.map((line, i) => (i === index ? { ...line, ...patch } : line)));
```

React decides whether to re-render by comparing references. Mutating in place leaves the reference
unchanged, so React concludes nothing happened and the screen does not update.

**Prefer the functional form** (`setState(prev => …)`) whenever the new value depends on the old one.
The direct form closes over the value from the render it was written in, which is stale if several
updates are queued in one tick.

## Props and lifting state up

Data flows **down** through props; changes flow **up** through callback props.

```tsx
<OrdersListPage onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)} />
```

The child does not know what selecting an order means. It reports the event; the parent decides. This
keeps components reusable and is what makes them straightforward to test.

When two siblings need the same state, it moves to their nearest common parent — "lifting state up."
When that parent is many levels away, **context** (`createContext` + `useContext`) passes it without
threading props through every intermediate layer.

## Lists and keys

```tsx
{data.content.map((order) => (
  <tr key={order.id} onClick={() => onSelectOrder(order.id)}>…</tr>
))}
```

`key` tells React which element corresponds to which item across renders, so it can move rows rather
than rebuild them.

**Use a stable identity from your data — never the array index.** Index keys break the moment the list
reorders or an item is removed: React reuses the wrong DOM node, and any state inside it (an input's
value, a scroll position, focus) attaches to the wrong row.

## Effects, and why you probably want fewer

`useEffect` runs code *after* render, for synchronizing with something outside React:

```tsx
useEffect(() => {
  const unsubscribe = subscribeToStream(url, handlers, ['order-status-changed']);
  return unsubscribe;   // cleanup: runs on unmount and before the next effect
}, [url]);
```

Three parts: the effect body, an optional **cleanup function** returned from it, and the **dependency
array** controlling when it re-runs (`[]` = once on mount; omitted = every render).

**The cleanup function is not optional for anything with a lifetime.** Subscriptions, timers, event
listeners, and open connections all leak without it — and in `StrictMode` React deliberately mounts,
unmounts, and remounts components in development specifically to make a missing cleanup visible.

The most common React mistake is using effects for things that are not synchronization:

- **Fetching data** — use a query library ([TanStack Query](tanstack-query.md)). Hand-rolled fetch
  effects have to reimplement caching, deduplication, race-condition handling, and cleanup, and
  usually get the race conditions wrong.
- **Deriving values from props or state** — just compute it during render. An effect that sets state
  from other state causes a second render and can desynchronize.
- **Responding to a user action** — put it in the event handler.

## Rules of hooks

Two, and they are absolute:

1. **Only call hooks at the top level** — never inside conditions, loops, or nested functions.
2. **Only call them from components or other hooks.**

React tracks hooks by call order. A conditional hook changes that order between renders and the state
of one hook gets handed to another. The ESLint rules that enforce this are worth having on.

## Custom hooks

A function whose name starts with `use` and which calls other hooks. That is the whole mechanism — it
is how you extract stateful logic for reuse:

```tsx
function useOrderStream(orderId: string) {
  const [status, setStatus] = useState<OrderStatus | null>(null);
  useEffect(() => { /* subscribe, return cleanup */ }, [orderId]);
  return status;
}
```

No special API, no registration. Just a function that follows the naming convention so the lint rules
can check it.
