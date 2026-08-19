import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Routes, Route, NavLink, Navigate, useNavigate, useParams } from 'react-router-dom';
import { OverviewPage } from './pages/OverviewPage';
import { OrdersListPage } from './pages/OrdersListPage';
import { CreateOrderPage } from './pages/CreateOrderPage';
import { OrderDetailPage } from './pages/OrderDetailPage';
import { ScenariosPage } from './pages/ScenariosPage';
import { ScenarioRunDetailPage } from './pages/ScenarioRunDetailPage';
import { EventExplorerPage } from './pages/EventExplorerPage';
import { SystemHealthPage } from './pages/SystemHealthPage';
import { ArchitecturePage } from './pages/ArchitecturePage';

// This is a demo/ops console, not a resilient consumer app — when a backend is down we want that
// surfaced immediately and honestly (frontend-design.md §28), not masked behind TanStack Query's
// default 3-attempt exponential backoff, and not silently deferred by the default
// networkMode: 'online' (which pauses a failed fetch until the browser reports 'online' again —
// harmless when the browser's online signal is reliable, but an unnecessary indirection here).
// retry: 0 + networkMode: 'always' means every query hits the network once and reports success or
// failure right away.
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 0, networkMode: 'always' } },
});

// Phase 5: seven pages replace the earlier state-based `view` switch in this file (list/create/
// detail only). A `useState` view switch does not scale to seven top-level pages plus nested
// detail routes (order detail, scenario-run detail) that should be independently deep-linkable
// (e.g. sharing a link straight to a scenario run) and back-button-navigable. React Router is a
// small, well-understood addition for exactly this — not adopted for its own sake, and nothing
// else in the app needed lifted global state that would argue for a heavier state library.
const NAV_ITEMS = [
  { to: '/', label: 'Overview', end: true },
  { to: '/orders', label: 'Orders' },
  { to: '/scenarios', label: 'Scenarios' },
  { to: '/events', label: 'Event Explorer' },
  { to: '/health', label: 'System Health' },
  { to: '/architecture', label: 'Architecture' },
];

function OrdersListRoute() {
  const navigate = useNavigate();
  return (
    <OrdersListPage
      onSelectOrder={(orderId) => navigate(`/orders/${orderId}`)}
      onCreateOrder={() => navigate('/orders/new')}
    />
  );
}

function CreateOrderRoute() {
  const navigate = useNavigate();
  return (
    <CreateOrderPage
      onOrderCreated={(orderId) => navigate(`/orders/${orderId}`)}
      onCancel={() => navigate('/orders')}
    />
  );
}

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div className="app">
          <header className="app-header">
            <NavLink to="/" className="app-title-link">
              <h1>Order Fulfillment Systems Lab</h1>
            </NavLink>
            <p className="app-subtitle">Event-driven Spring Boot / Kafka demonstration console</p>
            <nav className="app-nav">
              {NAV_ITEMS.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => `app-nav-link${isActive ? ' active' : ''}`}
                >
                  {item.label}
                </NavLink>
              ))}
            </nav>
          </header>

          <main>
            <Routes>
              <Route path="/" element={<OverviewPage />} />
              <Route path="/orders" element={<OrdersListRoute />} />
              <Route path="/orders/new" element={<CreateOrderRoute />} />
              <Route path="/orders/:orderId" element={<OrderDetailRoute />} />
              <Route path="/scenarios" element={<ScenariosPage />} />
              <Route path="/scenario-runs/:runId" element={<ScenarioRunDetailRoute />} />
              <Route path="/events" element={<EventExplorerPage />} />
              <Route path="/health" element={<SystemHealthPage />} />
              <Route path="/architecture" element={<ArchitecturePage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

function OrderDetailRoute() {
  const navigate = useNavigate();
  const orderId = useParamOrRedirect('orderId', '/orders');
  if (!orderId) return null;
  return <OrderDetailPage orderId={orderId} onBack={() => navigate('/orders')} />;
}

function ScenarioRunDetailRoute() {
  const navigate = useNavigate();
  const runId = useParamOrRedirect('runId', '/scenarios');
  if (!runId) return null;
  return <ScenarioRunDetailPage runId={runId} onBack={() => navigate('/scenarios')} />;
}

// Small helper: react-router's useParams is typed as possibly-undefined; every detail route in
// this app treats a missing param as "go back to the list" rather than rendering a broken page.
function useParamOrRedirect(key: string, fallback: string): string | null {
  const params = useParams();
  const navigate = useNavigate();
  const value = params[key];
  if (!value) {
    navigate(fallback, { replace: true });
    return null;
  }
  return value;
}

export default App;
