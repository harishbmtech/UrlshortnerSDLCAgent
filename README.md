# Agentic SDLC System — URL Shortener

This project turns a written requirement into reviewable engineering output: a plan, a design, code, tests, docs and a release decision. It is a **multi-agent system** built with **Spring AI**, **LangGraph4j** (LangGraph for Java) and **local Ollama models**. Eight specialist agents each run on the Ollama model suited to their job. Agents do the multi-step work within set limits on what they may do alone. Humans own the approvals and the final quality check.

The repository has two parts that work together:

| Part | Package | What it is |
|---|---|---|
| **URL shortener service** | `com.example.shortener` | The system being built: REST APIs, redirects, click analytics, expiry, custom aliases, rate limiting, and blocking of unsafe/private targets (SSRF). |
| **Multi-agent SDLC orchestrator** | `com.example.sdlc` | A stateful LangGraph4j graph of agents on Ollama models covering requirements → plan → codebase analysis → parallel design, risk and test work → implementation → validation → docs → release. It has human approval gates, bounded retries, fallback, rollback, safe-stop, policy guardrails, a hash-chained audit log and reliability metrics. |

The system can work on its own codebase. The **brownfield** scenario analyses the real shortener source and produces a patch. That patch compiles and passes its own tests alongside the existing suite.

---

## Quick start

Requirements: **JDK 21**, **[Ollama](https://ollama.com/download)**, and internet access to Maven Central. Maven itself is optional because `./mvnw` is included.

```bash
# 1. Local models (one-off, ~9 GB)
ollama serve                                  # skip if the Ollama app is already running
./scripts/ollama-setup.sh                     # pulls llama3.1:8b + qwen2.5-coder:7b and smoke-tests JSON output

# 2. Build, test, run
./mvnw verify                                 # unit + graph + Spring/Ollama wiring tests (no GPU needed)
./mvnw spring-boot:run                        # API on http://localhost:8080
curl -s localhost:8080/api/v1/sdlc/agents | jq  # which model serves each agent, Ollama health, missing models

# 3. Drive the three scenarios (needs curl + jq)
./scripts/demo.sh
```

Or run all three scenarios in one command, with the demo playing the human roles:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--sdlc.demo.enabled=true
```

**Everything in Docker** (Ollama, model pull and the app): `docker compose up --build`. On a Mac, run Ollama natively instead, because Docker can't use the Apple GPU and runs are much slower.

## Multi-agent model setup (Spring AI + Ollama)

Each LLM-backed node in the graph is its own agent. It has its own system prompt, its own typed output record, its own acceptance guard and its **own Ollama model**. One Spring AI `OllamaChatModel` serves all of them. `OllamaLlmGateway` routes each call to the agent's model through per-request `OllamaOptions`.

| Agent | Job | Default model |
|---|---|---|
| `requirements_analysis` | Interprets intent, flags ambiguity, writes testable acceptance criteria | `llama3.1:8b` |
| `planning` | Task DAG with dependencies and parallel waves | `llama3.1:8b` |
| `codebase_analysis` | Impacted modules, APIs, data flows (brownfield) | `qwen2.5-coder:7b` |
| `architecture_design` | Components, API/schema, key decisions | `llama3.1:8b` |
| `risk_assessment` | Risks, failure scenarios, trade-offs, mitigations | `llama3.1:8b` |
| `test_strategy` | Tests traced to acceptance criteria | `qwen2.5-coder:7b` |
| `implementation` | Production code, migrations, tests | `qwen2.5-coder:7b` |
| `documentation` | API docs, runbook, changelog | `llama3.1:8b` |

To change the models:
- Set `OLLAMA_REASONING_MODEL` and `OLLAMA_CODER_MODEL`, or
- Set a single agent in `application.yml` under `sdlc.llm.agent-models` (e.g. `"[implementation]": qwen2.5-coder:14b`).

Bigger models give better output if your machine has the memory: `qwen2.5-coder:14b` or `32b`, `qwen3:14b`, `llama3.3:70b`. Other settings: `OLLAMA_BASE_URL` points at a remote Ollama server, and `OLLAMA_PULL_STRATEGY=when_missing` makes the app pull both models on startup.

How local models are kept reliable:
- **Schema-constrained output.** Each agent's output record is turned into a JSON schema and sent as Ollama's `format`, so the model can only answer in that shape.
- **Output cleaning.** `<think>` blocks (qwen3 / deepseek-r1), markdown fences and chatter are removed before parsing.
- **Guards and fallback.** Output that fails parsing or the agent's guard is retried, then replaced by the agent's deterministic strategy. This is recorded in the audit log and metrics.
- **Model fallback.** If an agent's model isn't pulled, the default model is used. `/api/v1/sdlc/agents` lists the missing models and the `ollama pull` commands.
- **Liveness.** If Ollama is not running, the app still starts and every agent runs its deterministic strategy. When Ollama comes back, agents return to their models within 15 seconds. `OLLAMA_ENABLED=false` forces deterministic mode, which is useful for CI and reproducible demos.
- **Attribution.** Every decision names the model that made it, e.g. `agent:implementation/llm[ollama:qwen2.5-coder:7b]`.

Timing: on a laptop GPU (e.g. Apple M-series or RTX 3060+), a greenfield run takes a few minutes, mostly spent in `implementation`. CPU-only runs work but are slow. `LLM_READ_TIMEOUT` (default 5m) bounds each call.

> Run from the project root. The brownfield analysis reads `src/main/java/com/example/shortener`. You can override this with `SDLC_SOURCE_ROOT` and `SDLC_MIGRATIONS_ROOT`.

---

## The three scenarios

| Scenario | Requirement | What the graph does |
|---|---|---|
| **Greenfield** | "Build a new URL shortener service from scratch: create short links via a REST API, redirect by code, click analytics, optional link expiry and custom aliases." | Classifies the request as GREENFIELD and derives 6 testable acceptance criteria. Plans 11 tasks in 4 parallel waves. Skips impact analysis. Runs design, risk and test strategy in parallel. Risk is MEDIUM, so the design is **auto-approved** within the agents' limits. Produces the reference implementation, validation passes, docs are written, and the run **pauses for human release approval**. After approval it publishes the files with a manifest and audit trail. |
| **Brownfield** | "Add an optional max-clicks limit to existing short links: once a link has been followed N times it must stop redirecting and return 410 Gone." | Analyses the codebase by symbol references: 4 files to modify (`ShortUrl`, `ShortUrlService`, `ShortUrlDtos`, `ShortUrlController`) and 2 for regression review. It spots the schema change and **re-plans to v2** by adding a migration task. The anchored patches it produces keep the API backward compatible. They include a new `V2__add_max_clicks...sql` (released `V1` is never edited) and `MaxClicksLimitTest`. The demo injects one policy violation to show **retry with feedback**. A reviewer rejection shows **dynamic re-planning**. |
| **Ambiguous** | "Make the short links faster and more secure." | Flags `AMB-FAST` and `AMB-SECURE` as blocking ambiguities and **pauses at the clarification gate**. The human answers: p95 < 20 ms at 500 rps with caching, plus SSRF blocking and rate limiting. The agent re-analyses and finds that **SSRF protection and rate limiting already exist**, so it drops those tasks. Caching is rated **HIGH** risk (stale reads across replicas), so the design **goes to a human** for approval. It then generates `CachingShortUrlStore` (a decorator) with tests, and release approval follows. |

Every run produces an **engineering summary** covering the plan and rationale, codebase impact, design decisions, artifacts (with sha256 and lineage), validation, risks, trade-offs and failure scenarios, human checkpoints, the full decision lineage and execution path, assumptions and limitations. Get it from `GET /api/v1/sdlc/runs/{id}/summary`. After publishing it is also written to `build/sdlc-output/{runId}/ENGINEERING-SUMMARY.md`.

Real outputs from these runs, including the generated code and an audit trail, are in [docs/sample-runs](docs/sample-runs).

---

## Architecture (summary)

Full details, including the graph diagram, are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```
REST (SdlcController) ─► SdlcOrchestrator ─► LangGraph4j CompiledGraph<SdlcState>  (MemorySaver checkpoints)
                                              │  every node wrapped by GovernedNode:
                                              │  kill-switch · autonomy budget · audit (hash chain) · metrics · lineage
                                              ▼
 requirements ─?─► clarification_gate(H) ─► requirements        (bounded re-plan loop)
      └─► planning ─► codebase_analysis ═╦═► architecture_design ═╗
                                          ╠═► risk_assessment     ╠═► design_gate (join) ─?─► design_approval(H, if risk ≥ HIGH)
                                          ╚═► test_strategy      ═╝
      implementation ─► validation ─?─► implementation (≤3 attempts, violations fed back) ─?─► rollback ─► safe_stop
                                   └─► documentation ─► release_readiness ─?─► release_approval(H) ─?─► publish ─► summary
                                                                                           └─► planning (re-plan) / safe_stop
```

- **Agents** (`com.example.sdlc.agents`) implement LangGraph4j `NodeAction`s. The LLM goes through the `LlmGateway` port. `OllamaLlmGateway` is Spring AI's `OllamaChatModel` plus `AgentModelRoster` (per-agent model), `OllamaHealthProbe` (liveness and pulled models) and `BeanOutputConverter` (JSON schema → typed records).
- **Governance** (`com.example.sdlc.governance`) is deterministic and never delegated to an LLM:
  - a `PolicyEngine` with 9 guardrails (security, compliance, change control and QA)
  - a `ResilientExecutor` for retry → guard → fallback
  - a `HashChainedAuditLog`, `ReliabilityMetrics` and `RunControl` (the kill switch)
- **Human-in-the-loop** uses LangGraph `interruptBefore` on the three gates plus `updateState` and `GraphInput.resume()`. Approvers must be on an allow-list.

---
## SDLC Agent

<img width="1441" height="997" alt="image" src="https://github.com/user-attachments/assets/ab0d6e11-9079-43a8-82ee-4e9a7f48eb06" />



## API

| Method & path | Purpose |
|---|---|
| `POST /api/v1/urls` `{url, alias?, ttlSeconds?}` | Create a short link (201, Location). 400 for invalid/unsafe URLs, 409 for a taken alias, 429 when rate limited |
| `GET /{code}` | 302 redirect (`Cache-Control: no-store`). 404 unknown, 410 expired or disabled |
| `GET /api/v1/urls/{code}` · `/stats` · `DELETE` | Metadata · analytics (total, last 24h, top referrers) · disable |
| `POST /api/v1/sdlc/runs` `{requirement, faults?, async?}` | Start an SDLC run (runs until the first human gate or the end) |
| `GET /api/v1/sdlc/runs/{id}` | Status, pending gate, spec, plan, impact, risk, validation, artifacts, decisions |
| `POST /api/v1/sdlc/runs/{id}/decisions` `{gate, verdict: APPROVE\|REJECT\|ABORT, approver, comment, clarifications?}` | Human checkpoint |
| `POST /api/v1/sdlc/runs/{id}/stop` · `/resume` | Kill switch · resume a failed or halted run from its last checkpoint |
| `GET /api/v1/sdlc/runs/{id}/summary` · `/audit` · `/artifact?path=` | Engineering summary · verified audit chain · artifact content |
| `GET /api/v1/sdlc/agents` | Multi-agent roster: model per agent, Ollama reachability, missing models plus pull commands, per-agent LLM calls/failures/latency |
| `GET /api/v1/sdlc/metrics` · `/graph` | Reliability metrics · Mermaid diagram of the compiled graph |

**Fault injection** is available for demonstrations and tests:
- `"faults": {"policy.implementation": 1}` makes the first implementation attempt emit insecure code, which exercises retry with feedback.
- `{"policy.implementation": 9}` exhausts the retries, which exercises rollback and safe-stop.
- `{"transient.planning": 5}` makes an agent fail transiently, which exercises retry and fallback.

Fault injection is a demo and test hook. In production, strip or disable it at the API gateway.

---

## Testing approach

| Layer | Tests |
|---|---|
| Shortener domain (pure Java, in-memory store, fixed clock) | `ShortUrlServiceTest`, `UrlValidatorTest` (21 abuse cases, including SSRF and credential tricks), `TokenBucketRateLimiterTest` |
| Governance | `PolicyEngineTest` (every rule), `HashChainedAuditLogTest` (tamper detection), `PlanAndRequirementsTest` (DAG and cycle detection, ambiguity detection) |
| Orchestration end to end (the real LangGraph4j graph) | `SdlcOrchestratorScenarioTest`: the 3 scenarios, retry with feedback, rollback and safe-stop, transient failure → fallback, rejection → re-plan, kill switch, approver authorisation, abort |
| Ollama adapter | `OllamaLlmGatewayTest`: per-agent model routing, JSON-schema `format`, fallback to the default model when a model isn't pulled, liveness against a fake Ollama server, output cleaning (think blocks, fences) |
| Multi-agent graph on Ollama | `OllamaMultiAgentGraphTest`: **record and replay**. The greenfield run's agent outputs are replayed as Ollama JSON answers, so all 7 agents in that scenario run through the real gateway on their own models across the whole graph. Also: a model answering in prose is contained by fallback, and Ollama being down gives a deterministic run |
| LLM path | `LlmPathGovernanceTest`: a scripted model returns a **cyclic plan**, a **made-up file path** and an **under-rated risk**. The governance layer rejects or corrects each one. |
| HTTP / Spring wiring | `ShortUrlApiIntegrationTest`, `SdlcApiIntegrationTest` (MockMvc + H2 + Flyway), `OllamaWiringIntegrationTest`: the real Spring AI `OllamaChatModel` over HTTP against a fake Ollama server, checking that each agent's model reaches the wire |

---

### How this build was verified

The build environment could not reach Maven Central or the Ollama model registry, so verification used this setup:
- the **real LangGraph4j 1.8.27** runtime and **real Jackson 2.18.3** (the JSON library Spring AI uses for structured output), both compiled from source
- thin stubs for Spring and Jakarta
- a stub of Spring AI's `OllamaOptions` builder written from the Spring AI **v1.0.0 source**

Results under that setup:
- **All 47 unit, graph and Ollama-path tests pass.** This includes the three scenarios, every control loop, and the full graph with every agent served through `OllamaLlmGateway`.
- Those tests found and fixed one real bug: markdown fences inside generated docs cut the JSON short.

**Not executed here:**
- the three `@SpringBootTest` classes (HTTP, JPA, Flyway and Spring AI Ollama wiring), which are compile-checked only
- a run against a live Ollama model

Run `./mvnw verify`, then `./scripts/ollama-setup.sh && ./mvnw spring-boot:run`. Dependency versions are in `pom.xml` (Spring Boot 3.4.5, Spring AI 1.0.0, LangGraph4j 1.8.27).

## Key decisions and trade-offs

- **Deterministic validation, probabilistic generation.** The component that grades the work is never the LLM that produced it. The policy rules are pure functions, easy to audit and impossible for an agent to waive.
- **Local models, one per kind of work.** Ollama keeps requirements and code on your machine, with no API keys. A code-tuned model for code-heavy agents and a general model for the rest gets more out of 7-8B models than one model for everything. The cost is weaker reasoning and smaller context than hosted frontier models. That is why every model answer goes through schema-constrained decoding, guards, policy rules and human gates.
- **The LLM is never a single point of failure.** Deterministic strategies make the system reproducible, testable in CI and resilient to model outages. The cost is that offline code generation only covers catalogued features. Anything else gets an explicit change proposal instead of made-up code.
- **Human gates are placed by risk.** Design approval is required only when risk ≥ `HIGH` (configurable). Release approval is always required, because publishing is the high-impact action.
- **Everything is bounded:** ≤3 implementation attempts, ≤2 re-plans, ≤60 node executions per run, and 2 retries with exponential backoff per agent call.
- **Shortener design choices:**
  - random, non-enumerable codes rather than Base62(id)
  - 302 with `no-store` rather than 301, so analytics stay accurate and revocation is instant
  - atomic SQL counter increment
  - salted-hash IPs, so no raw personal data is stored
  - Flyway owns the schema
  - the domain sits behind a `ShortUrlStore` port, so storage can change without touching business rules

## Limitations

- 7-8B local models often fail the guards on the hardest step (multi-file brownfield patches). Those steps then fall back to the deterministic strategy, and the fallback is visible in the decision actor and in `/agents` stats. A 14B+ coder model improves this.
- The Postman collection's content assertions (task counts, files, plan versions) match deterministic mode. Run with `OLLAMA_ENABLED=false` for reproducible Postman runs. With Ollama on, the same flows and gates run, but model-written content varies.

- Checkpoints use LangGraph4j's in-memory `MemorySaver`, so a restart loses paused runs. For production, use a persistent saver (LangGraph4j ships Postgres and Redis savers).
- Generated code is checked statically: policy rules plus structural checks. It is compiled and tested by CI after publishing, not inside the graph.
- Brownfield impact analysis is based on symbol references plus catalogued feature knowledge (offline) or the LLM (online, filtered against the index). It is not a full compiler-grade call graph.
- The rate limiter and the hot-link cache are per instance. The docs describe the Redis and pub/sub upgrade path.
- Management APIs assume authentication at the API gateway. Approver identity is taken from the request body and checked against an allow-list, not tied to a real identity provider.

## Testing with Postman

Import `postman/Agentic-SDLC.postman_collection.json` into Postman. It has 38 requests with test assertions, in six folders: the shortener, the three scenarios, failure paths and observability.

1. Start the app with `./mvnw spring-boot:run`.
2. Right-click the collection and choose **Run**, then run all requests **in order**. Later requests reuse run ids that earlier ones save.
3. Keep **Automatically follow redirects** off (the collection sets this), so the redirect tests see the 302.

You can also run it from the command line: `npx newman run postman/Agentic-SDLC.postman_collection.json`.
