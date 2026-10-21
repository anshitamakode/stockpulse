const STATUS_LABEL = {
  ACTIVE: 'Active',
  PRICE_REVIEW_PENDING: 'Price review',
  OUT_OF_STOCK: 'Out of stock',
  DISCONTINUED: 'Discontinued',
};

export default function ProductTable({ products, suggestions, busy, actions }) {
  const working = new Set(suggestions.filter((s) => s.status === 'PROCESSING').map((s) => s.productId));

  return (
    <section className="panel products">
      <div className="panel-head">
        <h2>Inventory</h2>
        <span className="muted small">Sell to simulate orders. Low stock and spikes trigger the advisor automatically.</span>
      </div>

      {products.length === 0 ? (
        <p className="empty">No products yet.</p>
      ) : (
        <ul className="product-list">
          {products.map((p) => (
            <li key={p.id} className="product">
              <div className="product-main">
                <div className="product-name">
                  <strong>{p.name}</strong>
                  <span className="mono muted small">{p.id} · {p.category.toLowerCase()}</span>
                </div>
                <span className={`badge status-${p.status.toLowerCase()}`}>{STATUS_LABEL[p.status] ?? p.status}</span>
              </div>

              <div className="product-stats">
                <Stat label="Price" value={`$${Number(p.currentPrice).toFixed(2)}`} />
                <StockStat p={p} />
                <Stat
                  label="Velocity"
                  value={`${p.demandVelocity}/day`}
                  hint={p.demandVelocity > p.baselineVelocity ? `↑ from ${p.baselineVelocity}` : null}
                  hot={p.demandVelocity >= p.baselineVelocity * 1.3 && p.demandVelocity > p.baselineVelocity}
                />
                <Stat label="Cover" value={p.daysOfCover == null ? '—' : `${p.daysOfCover} d`} warn={p.daysOfCover != null && p.daysOfCover < 7} />
              </div>

              <div className="product-actions">
                <button disabled={p.stockLevel < 1 || busy[`order-${p.id}-1`]} onClick={() => actions.order(p, 1)}>
                  Sell 1
                </button>
                <button disabled={p.stockLevel < 5 || busy[`order-${p.id}-5`]} onClick={() => actions.order(p, 5)} title="A burst of 5 units usually counts as a demand spike">
                  Sell 5
                </button>
                <button disabled={busy[`restock-${p.id}`]} onClick={() => actions.restock(p, 50)}>
                  Restock +50
                </button>
                <button className="ghost" disabled={busy[`ask-${p.id}`] || working.has(p.id)} onClick={() => actions.ask(p)}>
                  {working.has(p.id) ? 'Advisor working…' : 'Ask advisor'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Stat({ label, value, hint, warn, hot }) {
  return (
    <div className={`stat ${warn ? 'warn' : ''} ${hot ? 'hot' : ''}`}>
      <span className="eyebrow">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="small muted">{hint}</span>}
    </div>
  );
}

function StockStat({ p }) {
  const max = Math.max(p.stockLevel, p.reorderThreshold * 2, 1);
  const pct = Math.min(100, (p.stockLevel / max) * 100);
  const mark = Math.min(100, (p.reorderThreshold / max) * 100);
  return (
    <div className={`stat ${p.belowThreshold ? 'warn' : ''}`}>
      <span className="eyebrow">Stock</span>
      <span className="stat-value">
        {p.stockLevel} <span className="muted small">/ {p.reorderThreshold} min</span>
      </span>
      <div className="meter" role="img" aria-label={`${p.stockLevel} in stock, reorder at ${p.reorderThreshold}`}>
        <div className="meter-fill" style={{ width: `${pct}%` }} />
        <div className="meter-mark" style={{ left: `${mark}%` }} />
      </div>
    </div>
  );
}
