import { useState } from 'react';
import SuggestionCard from './SuggestionCard.jsx';

const OPEN = new Set(['PROCESSING', 'PENDING']);

export default function SuggestionQueue({ suggestions, busy, onDecide }) {
  const [tab, setTab] = useState('open');
  const open = suggestions.filter((s) => OPEN.has(s.status));
  const history = suggestions.filter((s) => !OPEN.has(s.status));
  const list = tab === 'open' ? open : history;
  const pendingCount = open.filter((s) => s.status === 'PENDING').length;

  return (
    <section className="panel queue">
      <div className="panel-head">
        <h2>Suggestions</h2>
        <div className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === 'open'} className={tab === 'open' ? 'on' : ''} onClick={() => setTab('open')}>
            Needs decision{pendingCount ? <span className="count">{pendingCount}</span> : null}
          </button>
          <button role="tab" aria-selected={tab === 'history'} className={tab === 'history' ? 'on' : ''} onClick={() => setTab('history')}>
            History
          </button>
        </div>
      </div>

      {list.length === 0 ? (
        <p className="empty">
          {tab === 'open'
            ? 'Nothing to decide. Sell a low-stock item (try the T-shirt) or a burst of hoodies to trigger the advisor.'
            : 'Decided, skipped and superseded suggestions appear here.'}
        </p>
      ) : (
        <ul className="suggestion-list">
          {list.map((s) => (
            <SuggestionCard key={s.id} s={s} busy={busy[`decide-${s.id}`]} onDecide={onDecide} />
          ))}
        </ul>
      )}
    </section>
  );
}
