const LABELS = { ai: 'AI advisor', ruleBased: 'Rules' };

export default function Header({ strategy, onSwitch, switching, lastUpdated }) {
  const active = strategy?.active;
  return (
    <header className="header">
      <div className="brand">
        <span className="brand-mark" aria-hidden="true">◆</span>
        <div>
          <h1>StockPulse</h1>
          <p className="muted">Merchandising console</p>
        </div>
      </div>

      <div className="header-right">
        {strategy && (
          <div className="strategy">
            <span className="eyebrow">Strategy</span>
            <div className="segmented" role="group" aria-label="Recommendation strategy">
              {['ai', 'ruleBased'].map((name) => (
                <button
                  key={name}
                  className={active === name ? 'on' : ''}
                  aria-pressed={active === name}
                  disabled={switching || active === name}
                  onClick={() => onSwitch(name)}
                >
                  {LABELS[name] ?? name}
                </button>
              ))}
            </div>
            {active === 'ai' && (
              <span className={`llm ${strategy.llmConfigured ? 'ok' : 'warn'}`} title={[strategy.llmModel, ...(strategy.llmFallbackModels ?? [])].join(' → ')}>
                {strategy.llmConfigured
                  ? `${strategy.llmProvider} · ${strategy.llmModel}${
                      strategy.llmFallbackModels?.length ? ` (+${strategy.llmFallbackModels.length} backup)` : ''
                    }`
                  : 'No API key: every AI call falls back to rules'}
              </span>
            )}
          </div>
        )}
        <span className="updated muted" aria-live="polite">
          {lastUpdated ? `Updated ${lastUpdated.toLocaleTimeString()}` : 'Connecting…'}
        </span>
      </div>
    </header>
  );
}
