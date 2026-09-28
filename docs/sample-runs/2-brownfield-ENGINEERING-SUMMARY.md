# Engineering summary - run eac511de-d076-4038-9a2b-19607b687d64

**Outcome:** COMPLETED  
**Published to:** `build/sdlc-output/eac511de-d076-4038-9a2b-19607b687d64`

## 1. Requirement understanding
> Add an optional max-clicks limit to existing short links: once a link has been followed N times it must stop redirecting and return 410 Gone.

- **Classification:** BROWNFIELD
- **Features:** [Max-clicks limit]

**Acceptance criteria**
- AC-1: A link created with maxClicks=N redirects at most N times; afterwards GET /{code} returns 410 Gone
- AC-2: Links created without maxClicks behave exactly as before (backward compatible API, schema and data)
- AC-3: Existing behaviour and API contracts remain unchanged; the existing test suite stays green

## 2. Plan v4 and rationale
Brownfield: analysis gates design; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review. Added migration task after impact analysis.

| Task | Stage | Depends on | Impact | Title |
|---|---|---|---|---|
| T1 | ANALYSIS | [] | LOW | Analyse impact on the existing codebase |
| T2 | DESIGN | [T1] | MEDIUM | Design API contract and schema changes |
| T3 | DESIGN | [T1] | MEDIUM | Threat model and risk assessment |
| T4 | TEST | [T2] | LOW | Test strategy with acceptance-criteria traceability |
| T5 | IMPLEMENT | [T2] | MEDIUM | Implement: Max-clicks limit |
| T6 | IMPLEMENT | [T2] | MEDIUM | Address reviewer feedback: [release_approval by release-manager] Also return maxClicks in GET /api/v1/urls/{code} |
| T7 | TEST | [T5, T6, T4, T11] | MEDIUM | Unit and integration tests |
| T8 | TEST | [T5, T6] | MEDIUM | Regression suite on impacted modules |
| T9 | DOCS | [T5, T6] | LOW | API docs, runbook and changelog |
| T10 | RELEASE | [T7, T3, T8, T9] | HIGH | Release readiness review and human sign-off |
| T11 | IMPLEMENT | [T2] | MEDIUM | Add additive DB migration: ALTER TABLE short_url ADD COLUMN max_clicks BIGINT |

**Execution waves (parallelisable groups):** [[T1], [T2, T3], [T4, T5, T6, T11], [T7, T8, T9], [T10]]

## 3. Codebase impact (brownfield)
Impacted 5 file(s) (4 modified, 1 regression-review)

- `src/main/java/com/example/shortener/domain/ShortUrl.java` [domain] modify: declares ShortUrl; uses isResolvable
- `src/main/java/com/example/shortener/service/ShortUrlService.java` [service] modify: declares CreateCommand; uses isResolvable; uses resolve
- `src/main/java/com/example/shortener/web/ShortUrlDtos.java` [web] modify: declares CreateShortUrlRequest
- `src/main/java/com/example/shortener/web/ShortUrlController.java` [web] modify: constructs CreateCommand
- `src/main/java/com/example/shortener/web/RedirectController.java` [web] review: uses resolve

**APIs:** [GET /api/v1/urls/{code}, GET /api/v1/urls/{code}/stats, DELETE /api/v1/urls/{code}, POST /api/v1/urls, GET /{code:[A-Za-z0-9_-]+}]
**Schema:** [ALTER TABLE short_url ADD COLUMN max_clicks BIGINT]

**Data flows**
- HTTP request -> web controller -> ShortUrlService -> ShortUrlStore -> database
- Schema: new column read by the entity on every redirect lookup (hot path)

## 4. Design
Incremental change to the existing layered service touching 5 file(s). Features: [Max-clicks limit]

**Decisions**
- ADR: exhausted links return 410 Gone, consistent with expiry
- Trade-off: Nullable column + optional request field keeps old clients working; a default limit would have broken them
- Schema change is additive and nullable: zero-downtime, no backfill, old app versions keep working
- Backward compatibility: only optional request fields and additive columns; no endpoint is removed or renamed

## 5. Artifacts
| Type | Path | sha256 | Plan | Producer |
|---|---|---|---|---|
| API_SPEC | `docs/api/openapi.yaml` | eddb7ff4152b | v4 | design_gate |
| SCHEMA | `docs/design/schema-change.sql` | 6381ee74744e | v4 | design_gate |
| ADR | `docs/adr/ADR-eac511de-design.md` | 30331037467a | v4 | design_gate |
| DOC | `docs/test-plan.md` | 621e7b4f86c0 | v4 | design_gate |
| CODE | `src/main/java/com/example/shortener/domain/ShortUrl.java` | 494beb07c7d4 | v4 | implementation |
| CODE | `src/main/java/com/example/shortener/service/ShortUrlService.java` | 905c12042f0a | v4 | implementation |
| CODE | `src/main/java/com/example/shortener/web/ShortUrlDtos.java` | 213ba877b6fb | v4 | implementation |
| CODE | `src/main/java/com/example/shortener/web/ShortUrlController.java` | 660abc0bbb53 | v4 | implementation |
| MIGRATION | `src/main/resources/db/migration/V2__add_max_clicks_to_short_url.sql` | 6015fd0ca3f9 | v4 | implementation |
| TEST | `src/test/java/com/example/shortener/service/MaxClicksLimitTest.java` | cba2b406f06c | v4 | implementation |
| DOC | `docs/proposals/review-feedback-1.md` | 71e358d21c12 | v4 | implementation |
| DOC | `docs/CHANGE-SUMMARY.md` | 040ad317d675 | v4 | documentation |
| DOC | `docs/runbook.md` | 48307f8e939d | v4 | documentation |
| DOC | `docs/CHANGELOG.md` | ef18c9176e4a | v4 | documentation |

## 6. Validation
Attempt 1: **PASSED** (9 rules, 11 artifacts)


Rules: [SEC-001 No hard-coded secrets or keys in source, SEC-002 SQL must be parameterised (no string concatenation), SEC-003 No process execution or Java native deserialisation, CMP-001 No personal data (IP, e-mail, credentials) in logs, CHG-001 Writes restricted to src/, docs/ (no traversal, no absolute paths), CHG-002 Existing migrations are immutable; new migrations must be additive, CHG-003 Brownfield changes stay within the analysed impact set, QA-001 Acceptance criteria traced to tests; code changes include tests, QA-002 Generated Java is structurally complete]

**Earlier failed attempts (fed back to the implementation agent)**
- validation: plan v2 attempt 1 SEC-001 BLOCKER src/main/java/com/example/shortener/persistence/LegacyLinkLookup.java - Possible hard-coded secret; load it from the environment / secret manager
- validation: plan v2 attempt 1 SEC-002 BLOCKER src/main/java/com/example/shortener/persistence/LegacyLinkLookup.java - SQL built via string concatenation; use bind parameters

**Release readiness:** GO - awaiting human release approval (score 100)
- Policy validation passed (no blocker/major)
- All acceptance criteria traced to test cases
- Code changes ship with tests
- Every HIGH/CRITICAL risk has a mitigation
- Runbook with rollback procedure present
- Artifact lineage consistent with current plan v4 (no stale outputs)
- Design within autonomy boundary (no human design approval required)

## 7. Risks, trade-offs, failure scenarios (overall MEDIUM)
- **R-1 MEDIUM consistency** Check-then-act race: concurrent redirects can overshoot maxClicks by up to (concurrency - 1) -> _Accept small overshoot for v1 (documented); v2: conditional UPDATE ... WHERE click_count < max_clicks_
- **R-2 MEDIUM change** Schema migration on a large, hot table -> _Additive nullable column (metadata-only change), new V2 migration, no backfill_
- **R-3 MEDIUM regression** Changing isResolvable() affects every redirect -> _Regression tests for expiry/deactivation paths; null maxClicks keeps old behaviour_
- **R-4 MEDIUM regression** Change touches 5 existing file(s) on the redirect/create paths -> _Regression suite on impacted modules; deploy behind canary; one-click rollback to previous build_

**Trade-offs**
- Nullable column + optional request field keeps old clients working; a default limit would have broken them

**Failure scenarios**
- Database unavailable -> redirects fail with 5xx; readiness probe removes the instance; alert on error rate
- Random-code collision storm (keyspace exhaustion) -> bounded retry then 500; alert and raise code length
- Flyway migration fails at startup -> instance does not start (fail fast); previous version keeps serving
- Burst of concurrent redirects on an almost-exhausted link overshoots maxClicks

## 8. Human checkpoints
- release_approval: **REJECT** by release-manager at 2026-09-27T12:51:53.320827615Z - "Also return maxClicks in GET /api/v1/urls/{code}"
- release_approval: **APPROVE** by release-manager at 2026-09-27T12:51:53.486481856Z - "ok, follow-up ticket for the response field"

## 9. Decision lineage
1. **requirements_analysis** (agent:requirements_analysis/deterministic): Requirement is actionable (BROWNFIELD, 3 acceptance criteria) - _features=[Max-clicks limit]; clarifications applied=[]_
2. **planning** (agent:planning/deterministic): 9 tasks in 5 waves (initial plan) - _Brownfield: analysis gates design; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review._
3. **codebase_analysis** (agent:codebase_analysis/deterministic): Blast radius 5 files; schema changes 1; existing capabilities 0 - _Impacted 5 file(s) (4 modified, 1 regression-review)_
4. **codebase_analysis** (governance:codebase_analysis): Re-planned to v2 after impact analysis - _schemaChanges=[ALTER TABLE short_url ADD COLUMN max_clicks BIGINT], alreadySatisfied=[]_
5. **architecture_design** (agent:architecture_design/deterministic): 4 design decisions - _Incremental change to the existing layered service touching 5 file(s). Features: [Max-clicks limit]_
6. **risk_assessment** (agent:risk_assessment/deterministic): Overall risk MEDIUM (4 risks) - _floor=MEDIUM_
7. **test_strategy** (agent:test_strategy/deterministic): 9 test cases covering 3 acceptance criteria - _Test pyramid: fast deterministic unit tests on the framework-free domain, a thin layer of HTTP integration tests, explicit abuse cases; every acceptance criterion is traced to >= 1 case._
8. **design_gate** (governance:design_gate): Design auto-approved within autonomy boundary - _overall risk MEDIUM < threshold HIGH; baseline frozen with 4 artifacts_
9. **implementation** (agent:implementation/deterministic): Attempt 1: 5 code, 1 test, 1 other files - _Max-clicks limit: 6 files via verified template_
10. **validation** (governance:validation): Validation attempt 1 FAILED: 2 blocker, 0 major - _9 rules over 11 artifacts_
11. **implementation** (agent:implementation/deterministic): Attempt 2: 4 code, 1 test, 1 other files - _Max-clicks limit: 6 files via verified template | addressed feedback: validation: plan v2 attempt 1 SEC-002 BLOCKER src/main/java/com/example/shortener/persistence/LegacyLinkLookup.java - SQL built via string concatenation; use bind parameters_
12. **validation** (governance:validation): Validation attempt 2 PASSED (0 minor findings) - _9 rules over 10 artifacts_
13. **documentation** (agent:documentation/deterministic): 3 documents
14. **release_readiness** (governance:release_readiness): GO - awaiting human release approval - _score 100/100_
15. **release_approval** (human:release-manager): REJECT - _Also return maxClicks in GET /api/v1/urls/{code}_
16. **planning** (agent:planning/deterministic): 10 tasks in 5 waves (re-plan v3 (upstream change: review: [release_approval by release-manager] Also return maxClicks in GET /api/v1/urls/{code})) - _Brownfield: analysis gates design; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review._
17. **codebase_analysis** (agent:codebase_analysis/deterministic): Blast radius 5 files; schema changes 1; existing capabilities 0 - _Impacted 5 file(s) (4 modified, 1 regression-review)_
18. **codebase_analysis** (governance:codebase_analysis): Re-planned to v4 after impact analysis - _schemaChanges=[ALTER TABLE short_url ADD COLUMN max_clicks BIGINT], alreadySatisfied=[]_
19. **architecture_design** (agent:architecture_design/deterministic): 4 design decisions - _Incremental change to the existing layered service touching 5 file(s). Features: [Max-clicks limit]_
20. **risk_assessment** (agent:risk_assessment/deterministic): Overall risk MEDIUM (4 risks) - _floor=MEDIUM_
21. **test_strategy** (agent:test_strategy/deterministic): 9 test cases covering 3 acceptance criteria - _Test pyramid: fast deterministic unit tests on the framework-free domain, a thin layer of HTTP integration tests, explicit abuse cases; every acceptance criterion is traced to >= 1 case._
22. **design_gate** (governance:design_gate): Design auto-approved within autonomy boundary - _overall risk MEDIUM < threshold HIGH; baseline frozen with 4 artifacts_
23. **implementation** (agent:implementation/deterministic): Attempt 1: 4 code, 1 test, 2 other files - _Max-clicks limit: 6 files via verified template; reviewer feedback #1 -> change proposal (needs LLM or engineer)_
24. **validation** (governance:validation): Validation attempt 1 PASSED (0 minor findings) - _9 rules over 11 artifacts_
25. **documentation** (agent:documentation/deterministic): 3 documents
26. **release_readiness** (governance:release_readiness): GO - awaiting human release approval - _score 100/100_
27. **release_approval** (human:release-manager): APPROVE - _ok, follow-up ticket for the response field_
28. **publish** (governance:publish): Published 14 artifacts - _approved by release-manager; manifest with sha256 + lineage written_

**Execution path:** 01:requirements_analysis -> 02:planning -> 03:codebase_analysis -> 04:architecture_design -> 04:risk_assessment -> 04:test_strategy -> 07:design_gate -> 08:implementation -> 09:validation -> 10:implementation -> 11:validation -> 12:documentation -> 13:release_readiness -> 14:release_approval -> 15:planning -> 16:codebase_analysis -> 17:architecture_design -> 17:risk_assessment -> 17:test_strategy -> 20:design_gate -> 21:implementation -> 22:validation -> 23:documentation -> 24:release_readiness -> 25:release_approval -> 26:publish

**Control loop:** implementation attempts=1, re-plans=1, plan version=v4

## 10. Assumptions and limitations
- Java 21 / Spring Boot 3 service; H2 for local runs, PostgreSQL-compatible SQL for production
- Single region deployment; management API authentication is handled by the API gateway
- Changes must be backward compatible for existing clients and stored links
- Validation is static (policy rules + structural checks); generated code is compiled and tested by CI after publish, not inside the graph.
- Offline mode uses curated templates for known features; unknown features produce change proposals instead of speculative code.
- Checkpoints are in memory: a restart loses paused runs (swap MemorySaver for a persistent saver in production).
