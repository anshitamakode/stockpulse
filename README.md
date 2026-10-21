# StockPulse

A reactive commerce advisor for ShopStream. When inventory drops below a threshold or demand
spikes, StockPulse notices on its own, asks an AI advisor (or deterministic rules) for a **price
adjustment and a reorder quantity**, and puts both in front of merchandising for approval. Nobody
has to press a button for the advice to appear.

```
 order ──► stock − qty, snapshot, velocity ──► TriggerDetector ──► open PROCESSING suggestions
   (one transaction, deterministic)              LOW_STOCK │ DEMAND_SPIKE        │
                                                                                 ▼ after commit
                                                         @Async on a virtual thread
                                                         CommerceEngine → active strategy
                                                           ├─ ruleBased: pricing + reorder rules
                                                           └─ ai: LLM → parse → validate
                                                                   └─ invalid half → rules (AI_FALLBACK)
                                                                                 │
 console (polls every 4 s) ◄── PENDING suggestions with reasoning + confidence ◄─┘
   Accept → price updated (pricing) / reorder approved   ·   Reject → recorded
```

## Run it

**Prerequisites:** Java 21, Maven 3.9+, Node 18+.

```bash
# 1. Backend — http://localhost:8080 (H2 console at /h2-console, JDBC URL jdbc:h2:mem:stockpulse)
cd backend
export LLM_API_KEY=your-key          # optional; without it the AI strategy falls back to rules
mvn spring-boot:run

# 2. Frontend — http://localhost:5173
cd frontend
npm install
npm run dev
```

Tests: `cd backend && mvn test` (unit tests for rules, guardrails, AI validation, LLM retry and
model fallback, stuck-suggestion recovery and the state machines, plus an HTTP integration test of
the full async loop).

### LLM configuration

Defaults are in `backend/src/main/resources/application.properties` and can be overridden with
environment variables:

| Setting | Env var | Default | Notes |
|---|---|---|---|
| `llm.provider` | `LLM_PROVIDER` | `gemini` | `gemini`, `groq`, `ollama`, or `openai` (any OpenAI-compatible endpoint, e.g. LiteLLM) |
| `llm.model` | `LLM_MODEL` | `gemini-3.5-flash-lite` | Primary model |
| `llm.fallback-models` | `LLM_FALLBACK_MODELS` | `gemini-3.1-flash-lite` | Comma-separated, tried in order when the primary fails |
| `llm.base-url` | `LLM_BASE_URL` | `https://generativelanguage.googleapis.com` | Groq: `https://api.groq.com` · Ollama: `http://localhost:11434` · LiteLLM: `http://localhost:4000` |
| `llm.retries` | — | `1` | Extra attempts per model on timeouts, 429 and 5xx |
| `stockpulse.strategy` | `STOCKPULSE_STRATEGY` | `ai` | Startup strategy; switch at runtime from the console or `PATCH /strategy` |

**API key.** Never put it in `application.properties`. Either set `LLM_API_KEY` in your shell, or
create `backend/local.properties` (already git-ignored) containing `llm.api-key=...`. That file is
picked up automatically when you start the app from the `backend` folder. Without a key, the app
still works and every suggestion comes from the rules.

If a model stops answering, list the models your key can use and pick a fast one:
```powershell
(Invoke-RestMethod "https://generativelanguage.googleapis.com/v1beta/models" -Headers @{ "x-goog-api-key" = "<key>" }).models.name
```

## Demo

1. **Low stock.** On *Organic Cotton T-Shirt* (8 in stock, threshold 15) click **Sell 1**. The toast
   says a low-stock signal fired; two cards appear as *Generating…*, then turn into a price raise and
   a reorder quantity, each with reasoning, confidence and an *Auto · Low stock* badge.
2. **Approve.** Click **Set $26.99** (or whatever was suggested). The product's price updates and its
   status returns from *Price review* to *Active*.
3. **Demand spike.** On *Hoodie* (11 in stock, threshold 12, baseline 15/day) click **Sell 1** (low-stock
   suggestions open), then **Sell 5**. The burst crosses the spike rule, so the low-stock cards are
   **superseded** by *Auto · Demand spike* cards. The old ones move to History.
4. **Switch strategy.** Toggle *AI advisor ↔ Rules* in the header; the next suggestion uses the new
   strategy with no restart. Each card shows its source: **AI**, **Rules**, or **AI → rules fallback**
   (the note says why the AI answer was discarded). AI cards also show the model that answered and
   what the rules alone would have recommended, so you can see where the AI disagrees.
5. **Manual ask.** Click **Ask advisor** on a healthy product: the price is held and the reorder is
   *skipped* ("no reorder needed").

The same thing with curl:

```bash
curl -X POST localhost:8080/products/PRD-003/orders -H 'Content-Type: application/json' -d '{"quantity":1}'
curl 'localhost:8080/suggestions?status=PENDING'
curl -X PATCH localhost:8080/suggestions/pricing/<id> -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}'
for i in 1 2 3 4 5; do curl -s -X POST localhost:8080/products/PRD-008/orders -H 'Content-Type: application/json' -d '{"quantity":1}' > /dev/null; done
curl -X PATCH localhost:8080/strategy -H 'Content-Type: application/json' -d '{"strategy":"ruleBased"}'
```

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/products` | All products with derived status and days of cover |
| GET | `/products/{id}` | One product |
| GET | `/products/{id}/snapshots?limit=20` | Inventory history (orders, restocks, price changes) |
| POST | `/products/{id}/orders` `{quantity}` | Simulate a sale → 201 with any signal detected and suggestions opened |
| POST | `/products/{id}/restock` `{quantity}` | Simulate a delivery |
| POST | `/products/{id}/suggestions` | Manual "ask the advisor" → 202 with PROCESSING suggestions; 409 if already open |
| GET | `/suggestions?status=PENDING,PROCESSING` | Unified queue (pricing + reorder), newest first |
| PATCH | `/suggestions/{pricing\|reorder}/{id}` `{status: ACCEPTED\|REJECTED}` | Decide; 409 if not PENDING or the price moved meanwhile |
| GET / PATCH | `/strategy` `{strategy: ai\|ruleBased}` | Read or switch the active strategy at runtime |

Errors are RFC 9457 `ProblemDetail` JSON: 400 (validation, unknown strategy), 404, 409 (state
conflicts, insufficient stock, concurrent updates).

## How it's built

```
backend/src/main/java/com/stockpulse
├── domain/      Product, InventorySnapshot, PricingSuggestion, ReorderSuggestion + enums
│                (SuggestionStatus and ProductStatus hold the state machines)
├── engine/      CommerceStrategy, RuleBasedCommerceStrategy, AiCommerceStrategy, CommerceEngine,
│                RulePricingPolicy, RuleReorderPolicy, PriceGuardrails, ProductContext
├── ai/          LlmClient, LLMGateway (Gemini/Groq/Ollama/OpenAI-compatible, retries + backup models),
│                prompt builder, parser
├── service/     InventoryService, TriggerDetector, DemandTracker, SuggestionService,
│                SuggestionGenerationService (async after-commit listener),
│                StaleSuggestionSweeper (recovers stuck suggestions)
├── web/         thin controllers, record DTOs, GlobalExceptionHandler
└── config/      StockPulseProperties (every threshold), async virtual-thread executor
frontend/src     App (polling), Header (strategy toggle), ProductTable, SuggestionQueue/Card
```

Layering is controller → service → repository → entity. Controllers are thin, entities never leave
the API (record DTOs only), and injection is constructor-only. Strategies receive an immutable
`ProductContext`, never entities, so they are pure and unit-tested without Spring.

**Key rules** (all tunable under `stockpulse.*`):

- *Low stock*: stock < reorder threshold.
- *Demand spike*: ≥ 5 units sold in the last 10 minutes **and** velocity ≥ 1.3 × baseline.
  Velocity = baseline + units sold in the window.
- *Guardrails*: no suggestion, AI or rules, may move the price more than ±20 % or below $1.00.
- *Reorder*: `ceil(velocity × (7-day lead + 14-day cover)) + threshold − stock`, rounded up to
  packs of 5, capped at 2000. During a spike only half the excess velocity counts.
- *Supersede*: spike > low stock > manual. A stronger signal replaces weaker open suggestions;
  an equal or weaker one is ignored, so repeated orders never flood the queue.

See [ADR.md](ADR.md) for why each of these choices was made and what was rejected.

## How I used AI

> Edit this section so it reflects your own process.

I used AI as a pair programmer: to scaffold the Spring Boot layers and React components from my
architecture rules, to draft the rule tables and test cases, and to review edge cases (duplicate
signals, superseding, concurrent accepts, LLM output that breaks guardrails). I made the design
decisions recorded in the ADR, checked every rule against the seed data by hand, and treated the
unit tests on rules, guardrails and AI validation as the definition of done. Inside the product,
the LLM only *recommends*: detection is deterministic, every AI answer is validated, and rules are
the fallback.

## Known limitations

- **Events are in-memory.** A sweeper re-runs suggestions stuck in PROCESSING (after 2 min) and fails
  them after 10 min, but a multi-instance deployment would want a transactional outbox.
- **Polling, not push.** The console polls every 4 s. SSE would cut latency and load.
- **Velocity is a toy model** (baseline + recent window) and is only recomputed on an order.
- **Accepting a reorder doesn't create a purchase order yet** (sprint 2). Use *Restock* to simulate delivery.
- **No auth or audit trail** of who accepted what.
- **H2 only.** Data resets on restart. The JPA model is portable to Postgres.
- **Single instance.** The strategy switch lives in memory, per JVM.
