# Architecture Decision Record: StockPulse sprint 1

Status: accepted · Scope: inventory signal → AI recommendation → human approval, for pricing and replenishment.

---

## ADR-1 · Suggestions are generated asynchronously, after the order commits

**Context.** An order should trigger advice without anyone pressing a button. Generating advice may
call an LLM that takes 1–10 s or times out. The order itself must stay fast and must never be lost
because the advisor is slow or down.

**Decision.** The order transaction updates stock, writes a snapshot, runs deterministic trigger
detection and creates `PROCESSING` suggestion rows. It then publishes a `SuggestionsOpenedEvent`.
A `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` handler on a virtual thread builds the
context, calls the strategy **outside any transaction**, and writes each result in its own short
transaction.

**Alternatives.**
- *Synchronous call in the request.* Simple, but couples order latency and availability to the LLM.
- *Message broker (Kafka/RabbitMQ).* Durable and scalable, but heavy infrastructure for one service in sprint 1.
- *Transactional outbox + poller.* Durable with no broker. It's the right next step but more moving parts than this sprint needs.

**Consequences.**
- Orders return in milliseconds, and the console shows "Generating…" right away because the rows exist.
- AFTER_COMMIT means a rolled-back order never produces advice, and the listener always finds its rows.
- Events are in-memory, so a crash between commit and generation would strand rows in `PROCESSING`.
  `StaleSuggestionSweeper` covers this: every minute it re-dispatches rows stuck over 2 minutes
  (results for rows that finished meanwhile are discarded) and marks rows over 10 minutes `FAILED`.
  A transactional outbox remains the durable upgrade for multi-instance deployments.
- Virtual threads make I/O-bound waits cheap without sizing a pool.

---

## ADR-2 · Rules decide *whether* to advise; AI only recommends *what*, inside hard limits

**Context.** Merchandising has to trust the system, and an LLM can return malformed JSON, invent
facts, or suggest a 300 % price jump. The brief asks for business decisions to be deterministic
where possible.

**Decision.**
- **Detection is deterministic.** `TriggerDetector` fires `LOW_STOCK` (stock < threshold) or
  `DEMAND_SPIKE` (≥ N units in the window and velocity ≥ ratio × baseline). No AI is involved.
- **Guardrails are code.** `PriceGuardrails` caps any price move at ±20 % with a $1 floor. Reorder
  quantities must be whole numbers within 0–2000.
- **AI output is validated field by field.** That covers the action enum, a price inside the band
  and consistent with the action (RAISE strictly up, HOLD exactly equal), confidence in 0–1,
  non-blank reasoning, and an integral in-range quantity.
- **Fallback is per half.** If the pricing part fails validation, rules replace only the pricing
  part (`AI_FALLBACK`, with the reason stored), and a valid AI reorder is kept. Transport or JSON
  failures fall back for both.
- **A human approves every change.** Accepting re-checks that the live price still equals the
  suggestion's `basePrice`.

**Alternatives.**
- *Let the LLM decide triggers too.* Unpredictable, untestable, and an API call per order.
- *Clamp out-of-band AI prices instead of rejecting them.* Clamping silently changes what the model
  "said" and hides model problems. Rejecting with a recorded reason is honest and visible in the UI.
- *Reject the whole AI answer on any error.* That throws away good reorder advice because of a bad price.

**Consequences.**
- The system is always useful, even with no API key, because rules answer.
- Every AI answer shown has passed the same limits the rules obey.
- The fallback reason is surfaced to merchandisers and logs, so model quality is measurable.

---

## ADR-3 · Pluggable strategy with a runtime switch

**Context.** The rule-based and AI-powered implementations must be interchangeable and switchable
without a restart. More strategies are expected later (competitor-aware, forecast-driven).

**Decision.** `CommerceStrategy` returns a `CommerceRecommendation` (pricing + reorder) from an
immutable `ProductContext`. `CommerceEngine` collects every strategy bean by `name()` and holds the
active one in an `AtomicReference`. `PATCH /strategy` swaps it, and unknown names return 400.

**Alternatives.**
- *Feature flag read on every call* (property refresh / config server). More infrastructure for the same effect.
- *Separate pricing and reorder strategy interfaces.* More flexible, but it doubles LLM calls and
  splits one coherent judgement ("raise price *and* reorder 300") into two.

**Consequences.**
- Switching is lock-free and takes effect on the next suggestion. In-flight generation finishes with
  the strategy it started with.
- Adding a strategy means adding a bean, with no engine changes.
- The switch is per instance and in memory. A multi-instance deployment would persist it.

---

## ADR-4 · One LLM call per signal; the model never sees the rule answer

**Context.** Both a price and a reorder quantity are needed per signal. Latency, cost and
consistency matter.

**Decision.** `AiCommerceStrategy` computes the rule answer first, then makes **one** call. The
system prompt fixes the role, how to weigh the trade-offs, the constraints and a strict JSON schema.
The user prompt is a JSON fact sheet: product numbers, velocity vs baseline, days of cover, recent
history, the replenishment policy (lead time, target cover, safety stock, case pack), the guardrail
band, and trigger-specific guidance (spike: "don't treat a short spike as permanent demand").
The rule answer is **withheld** from the prompt. It is stored next to the AI answer
(`ruleBaseline`, shown in the console as "Rules alone would: …") together with the model that
answered. Temperature is 0.2. The parser strips `<think>` blocks and markdown fences.

**Alternatives.**
- *Free-text answers.* Unparseable and unvalidatable.
- *Provider function calling / JSON mode only.* Not uniform across Groq, Gemini, Ollama and LiteLLM.
  The defensive parser works everywhere, and Gemini additionally gets `responseMimeType=application/json`.
- *Showing the rule answer as a reference.* Tried first. The model echoed it almost every time
  ("matches the rule baseline"), so the AI added cost without adding judgement.

**Consequences.**
- Half the latency and cost of two calls, and price and quantity are reasoned about together.
- AI and rules are now independent opinions, and every AI card shows where they differ, which
  makes the AI's contribution visible and measurable.
- The guardrails and validation (ADR-2), not the prompt, keep the model's answers safe.
- Provider-specific code is confined to `LLMGateway` behind the narrow `LlmClient` interface, which
  also makes the strategy unit-testable with a lambda stub.

---

## ADR-5 · Explicit state machines, supersede-by-priority, optimistic locking

**Context.** Signals repeat: every order below threshold is "low stock" again. A spike can arrive
while low-stock advice is still pending. Two merchandisers may click Accept at the same time.

**Decision.**
- `SuggestionStatus` declares its allowed transitions
  (`PROCESSING → PENDING | FAILED | SKIPPED | SUPERSEDED`, `PENDING → ACCEPTED | REJECTED | SUPERSEDED`;
  the rest are terminal). `Suggestion.transitionTo` is the only way to change status.
- `ProductStatus` is **derived** from facts (stock, open pricing suggestion), with `DISCONTINUED` sticky.
- Opening is idempotent per product and kind: a trigger opens a new suggestion only if it
  **outranks** every open one (spike > low stock > manual), superseding them. Otherwise it's ignored.
- `@Version` on suggestions and products. Conflicting writes return 409, and the async writer
  discards results for rows that are no longer `PROCESSING`.
- A reorder of quantity 0 is recorded as `SKIPPED` rather than shown as an action item.

**Alternatives.**
- *Status as a free-form string or setter.* Invalid moves become possible, such as accepting a
  `PROCESSING` suggestion.
- *Pessimistic locks.* They would hold row locks while waiting on users or the LLM.
- *Always open new suggestions.* The queue floods during a burst of orders.

**Consequences.**
- The queue shows exactly one live pricing and one live reorder suggestion per product, for the
  strongest current signal. Superseded advice stays in History for audit.
- Concurrency bugs surface as clean 409s instead of lost updates.
- Sprint 2 states (e.g. `ORDERED` once POs exist) are one map entry away.

---

## ADR-6 · Polling over SSE/WebSockets for the console

**Context.** New suggestions appear asynchronously and the console must show them without a refresh.

**Decision.** The React console polls products, suggestions and strategy every 4 s with plain
`fetch`, and refreshes immediately after any action.

**Alternatives.**
- *Server-Sent Events.* Lower latency and less load. It needs an emitter registry and reconnect
  handling, and doesn't work through every proxy without tuning.
- *WebSockets.* Bidirectional, which isn't needed here.

**Consequences.**
- Trivial to build and debug, and stateless on the server.
- Up to 4 s of extra latency (generation usually takes longer anyway). This is cheap at demo scale and
  wasteful at large scale, so SSE is the documented upgrade path.

---

## ADR-7 · H2 in memory for the sprint, Postgres in production

**Context.** Reviewers must be able to clone and run with zero setup. Real deployments need durability.

**Decision.** H2 in-memory with `ddl-auto=create-drop` and a `CommandLineRunner` seed of the brief's
8 products. JPA mappings avoid H2-only features: string enums, `BigDecimal` money, UUID keys, and an
index on `(product_id, captured_at)` for the velocity window query.

**Alternatives.**
- *Postgres via Docker Compose now.* More realistic, but every reviewer needs Docker.
- *Testcontainers only for tests.* Worth adding with Flyway in sprint 2.

**Consequences.**
- One command to run, and data resets on restart.
- Moving to Postgres means a datasource change plus Flyway migrations. The product attributes map
  (an `ElementCollection`) is the sprint 2 extension point for cost, competitor price and supplier
  lead time without a schema change.

---

## ADR-8 · LLM resilience: classify failures, retry, then a backup model, then rules

**Context.** In testing, the larger Gemini models returned 503s or did not answer within 90 seconds
while a lite model answered in about a second. Without resilience, every outage became an
immediate rules fallback, and an unknown model name failed the same way as a network blip.

**Decision.** `LLMGateway` classifies each failure and acts on it:
- **Retryable** (timeout, connection reset, 429, 5xx): retry the same model once with linear backoff.
- **Next model** (404 retired model, empty or blocked reply, non-JSON body): skip straight to the
  next model in `llm.fallback-models`, since retrying won't help.
- **Fatal** (missing key, 400/401/403): stop immediately, since no model will succeed.

Only when every model is exhausted does the strategy fall back to rules. The fallback reason then
lists every attempt (`primary → HTTP 503; backup → timeout`). The model that actually answered is
recorded on the suggestion. The per-call timeout is 15 s, so the worst case (2 models × 2 attempts)
stays well under the sweeper's 2-minute stale threshold.

**Alternatives.**
- *Resilience4j retry and circuit breaker.* A good fit at scale, but another dependency and config
  surface for three rules that fit in 30 lines.
- *Fall back to rules on the first failure.* Simple, but it wastes the AI on every transient blip.
- *Long timeouts.* They hide outages and make the console feel frozen.

**Consequences.**
- Transient provider trouble is usually invisible to merchandisers.
- A wrong model name or revoked key fails fast with a clear reason instead of slow retries.
- The retry policy is unit-tested without HTTP by overriding `callOnce`.
- A circuit breaker (skip a model for a minute after repeated failures) is the next step if traffic grows.
