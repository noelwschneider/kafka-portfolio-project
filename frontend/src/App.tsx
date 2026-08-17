import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { OrdersListPage } from './pages/OrdersListPage';
import { CreateOrderPage } from './pages/CreateOrderPage';
import { OrderDetailPage } from './pages/OrderDetailPage';

const queryClient = new QueryClient();

type View = { name: 'list' } | { name: 'create' } | { name: 'detail'; orderId: string };

function App() {
  const [view, setView] = useState<View>({ name: 'list' });

  return (
    <QueryClientProvider client={queryClient}>
      <div className="app">
        <header className="app-header">
          <h1 onClick={() => setView({ name: 'list' })}>Order Fulfillment Systems Lab</h1>
          <p className="app-subtitle">Phase 1 — modular monolith, synchronous workflow</p>
        </header>

        <main>
          {view.name === 'list' && (
            <OrdersListPage
              onSelectOrder={(orderId) => setView({ name: 'detail', orderId })}
              onCreateOrder={() => setView({ name: 'create' })}
            />
          )}
          {view.name === 'create' && (
            <CreateOrderPage
              onOrderCreated={(orderId) => setView({ name: 'detail', orderId })}
              onCancel={() => setView({ name: 'list' })}
            />
          )}
          {view.name === 'detail' && (
            <OrderDetailPage orderId={view.orderId} onBack={() => setView({ name: 'list' })} />
          )}
        </main>
      </div>
    </QueryClientProvider>
  );
}

export default App;
