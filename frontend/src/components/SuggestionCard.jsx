const TRIGGER = {
  LOW_STOCK: { label: 'Auto · Low stock', cls: 'auto' },
  DEMAND_SPIKE: { label: 'Auto · Demand spike', cls: 'spike' },
  MANUAL: { label: 'Manual request', cls: 'manual' },
};

const SOURCE = {
  AI: { label: 'AI', cls: 'ai' },
  RULE_BASED: { label: 'Rules', cls: 'rule' },
  AI_FALLBACK: { label: 'AI → rules fallback', cls: 'fallback' },
};

const money = (v) => `$${Number(v).toFixed(2)}`;

export default function SuggestionCard({ s, busy, onDecide }) {
  const trigger = TRIGGER[s.triggerReason] ?? { label: s.triggerReason, cls: '' };
  const source = s.source ? SOURCE[s.source] : null;

  return (
    <li className={`suggestion ${s.status.toLowerCase()}`}>
      <div className="suggestion-top">
        <span className="kind">{s.kind === 'PRICING' ? 'Price' : 'Reorder'}</span>
        <strong className="suggestion-product">{s.productName}</strong>
        <span className={`chip trigger ${trigger.cls}`}>{trigger.label}</span>
      </div>

      {s.status === 'PROCESSING' ? (
        <div className="generating">
          <span className="dots" aria-hidden="true"><i /><i /><i /></span>
          Generating recommendation…
        </div>
      ) : (
        <>
          <div className="headline">{headline(s)}</div>

          <div className="meta">
            {source && (
              <span className={`chip source ${source.cls}`} title={s.fallbackReason || undefined}>
                {source.label}
              </span>
            )}
            {s.status !== 'FAILED' && (
              <span className="confidence" title="Confidence">
                <span className="confidence-bar"><span style={{ width: `${Math.round(s.confidence * 100)}%` }} /></span>
                {Math.round(s.confidence * 100)}%
              </span>
            )}
            {s.status !== 'PENDING' && <span className={`badge outcome-${s.status.toLowerCase()}`}>{s.status.toLowerCase()}</span>}
          </div>

          <p className="reasoning">{s.reasoning}</p>
          {s.source === 'AI' && (s.aiModel || s.ruleBaseline) && (
            <p className="compare small muted">
              {s.aiModel && <span className="mono">{s.aiModel}</span>}
              {s.aiModel && s.ruleBaseline && ' · '}
              {s.ruleBaseline && <>Rules alone would: <b>{s.ruleBaseline}</b></>}
            </p>
          )}
          {s.fallbackReason && <p className="fallback-note small">Why rules decided: {s.fallbackReason}</p>}

          {s.status === 'PENDING' && (
            <div className="decide">
              <button className="primary" disabled={busy} onClick={() => onDecide(s, 'ACCEPTED')}>
                {acceptLabel(s)}
              </button>
              <button className="ghost" disabled={busy} onClick={() => onDecide(s, 'REJECTED')}>
                Reject
              </button>
            </div>
          )}
        </>
      )}
      <span className="time small muted">{new Date(s.createdAt).toLocaleTimeString()}</span>
    </li>
  );
}

function headline(s) {
  if (s.kind === 'PRICING') {
    if (!s.recommendedPrice) return <span className="muted">No price recommendation</span>;
    if (s.action === 'HOLD') return <>Hold at <b>{money(s.basePrice)}</b></>;
    const up = s.action === 'RAISE';
    return (
      <>
        {money(s.basePrice)} <span className="arrow">→</span> <b>{money(s.recommendedPrice)}</b>{' '}
        <span className={`delta ${up ? 'up' : 'down'}`}>{up ? '+' : ''}{s.priceChangePct}%</span>
      </>
    );
  }
  if (s.recommendedQuantity == null) return <span className="muted">No reorder recommendation</span>;
  if (s.recommendedQuantity === 0) return <>No reorder needed <span className="muted small">(stock {s.stockAtRequest})</span></>;
  return (
    <>
      Reorder <b>{s.recommendedQuantity}</b> units <span className="muted small">(stock {s.stockAtRequest} when asked)</span>
    </>
  );
}

function acceptLabel(s) {
  if (s.kind === 'REORDER') return 'Approve reorder';
  return s.action === 'HOLD' ? 'Keep price' : `Set ${money(s.recommendedPrice)}`;
}
