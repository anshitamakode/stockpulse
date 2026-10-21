import { useCallback, useEffect, useRef, useState } from 'react';
import * as api from './services/api.js';
import Header from './components/Header.jsx';
import ProductTable from './components/ProductTable.jsx';
import SuggestionQueue from './components/SuggestionQueue.jsx';
import ErrorBanner from './components/ErrorBanner.jsx';
import Toast from './components/Toast.jsx';

const POLL_MS = 4000;

export default function App() {
  const [products, setProducts] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [strategy, setStrategy] = useState(null);
  const [error, setError] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [busy, setBusy] = useState({}); // key -> true while an action is in flight
  const [toast, setToast] = useState(null);
  const loadingRef = useRef(false);
  const closeToast = useCallback(() => setToast(null), []);

  const load = useCallback(async () => {
    if (loadingRef.current) return;
    loadingRef.current = true;
    try {
      const [p, s, st] = await Promise.all([api.getProducts(), api.getSuggestions(), api.getStrategy()]);
      setProducts(p);
      setSuggestions(s);
      setStrategy(st);
      setError(null);
      setLastUpdated(new Date());
    } catch (e) {
      setError(e.status ? e.message : 'Cannot reach the StockPulse API. Is the backend running on :8080?');
    } finally {
      loadingRef.current = false;
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  // Runs an action, shows its outcome, then refreshes immediately instead of waiting for the poll.
  const run = useCallback(
    async (key, action, describe) => {
      setBusy((b) => ({ ...b, [key]: true }));
      try {
        const result = await action();
        const message = describe?.(result);
        if (message) setToast({ kind: 'ok', message });
      } catch (e) {
        setToast({ kind: 'error', message: e.message });
      } finally {
        setBusy((b) => ({ ...b, [key]: false }));
        load();
      }
    },
    [load]
  );

  const actions = {
    order: (p, qty) =>
      run(`order-${p.id}-${qty}`, () => api.placeOrder(p.id, qty), (r) => {
        const sold = `Sold ${qty} × ${p.name}`;
        if (!r.triggerDetected) return `${sold}.`;
        const signal = r.triggerDetected === 'DEMAND_SPIKE' ? 'Demand spike' : 'Low stock';
        return r.opened.length
          ? `${sold}. ${signal} detected, advisor is working on ${r.opened.length} suggestion${r.opened.length > 1 ? 's' : ''}.`
          : `${sold}. ${signal} detected; suggestions already open.`;
      }),
    restock: (p, qty) => run(`restock-${p.id}`, () => api.restock(p.id, qty), () => `Restocked ${qty} × ${p.name}.`),
    ask: (p) =>
      run(`ask-${p.id}`, () => api.requestSuggestions(p.id), () => `Asked the advisor about ${p.name}.`),
    decide: (s, status) =>
      run(`decide-${s.id}`, () => api.decideSuggestion(s.kind, s.id, status), () =>
        status === 'ACCEPTED'
          ? s.kind === 'PRICING'
            ? s.action === 'HOLD'
              ? `Kept ${s.productName} at $${s.basePrice}.`
              : `${s.productName} is now $${s.recommendedPrice}.`
            : `Reorder of ${s.recommendedQuantity} × ${s.productName} approved.`
          : `Rejected ${s.kind.toLowerCase()} suggestion for ${s.productName}.`
      ),
    strategy: (name) =>
      run('strategy', () => api.setStrategy(name), (r) => `Strategy switched to ${r.active === 'ai' ? 'AI advisor' : 'rules'}.`),
  };

  return (
    <div className="app">
      <Header strategy={strategy} onSwitch={actions.strategy} switching={busy.strategy} lastUpdated={lastUpdated} />
      {error && <ErrorBanner message={error} onRetry={load} />}
      <main className="layout">
        <ProductTable products={products} suggestions={suggestions} busy={busy} actions={actions} />
        <SuggestionQueue suggestions={suggestions} busy={busy} onDecide={actions.decide} />
      </main>
      {toast && <Toast {...toast} onClose={closeToast} />}
    </div>
  );
}
