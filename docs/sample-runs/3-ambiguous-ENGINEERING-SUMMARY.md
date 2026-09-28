# Engineering summary - run 37b4c4ab-01d4-4f63-8dfc-e334e1935425

**Outcome:** COMPLETED  
**Published to:** `build/sdlc-output/37b4c4ab-01d4-4f63-8dfc-e334e1935425`

## 1. Requirement understanding
> Make the short links faster and more secure.

- **Classification:** BROWNFIELD
- **Features:** [Rate limiting, Unsafe target protection, Hot-link caching]

**Acceptance criteria**
- AC-1: Link creation is limited per client with a token bucket; excess requests receive 429 with Retry-After
- AC-2: Targets on loopback/private/link-local networks, URLs with embedded credentials and non-http(s) schemes are rejected with 400
- AC-3: Redirect lookups for hot links are served from a bounded in-process cache with a short TTL; updates (e.g. deactivation) invalidate the entry immediately
- AC-4: Existing behaviour and API contracts remain unchanged; the existing test suite stays green

**Non-functional**
- From clarification AMB-SECURE: block private-network (SSRF) targets and rate-limit link creation
- From clarification AMB-FAST: p95 redirect latency under 20 ms at 500 requests/second by caching hot links in-process

**Clarifications from humans**
- AMB-SECURE: block private-network (SSRF) targets and rate-limit link creation
- AMB-FAST: p95 redirect latency under 20 ms at 500 requests/second by caching hot links in-process

## 2. Plan v2 and rationale
Brownfield: analysis gates design; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review. Dropped tasks already satisfied by existing code: [T5, T6].

| Task | Stage | Depends on | Impact | Title |
|---|---|---|---|---|
| T1 | ANALYSIS | [] | LOW | Analyse impact on the existing codebase |
| T2 | DESIGN | [T1] | MEDIUM | Design API contract and schema changes |
| T3 | DESIGN | [T1] | MEDIUM | Threat model and risk assessment |
| T4 | TEST | [T2] | LOW | Test strategy with acceptance-criteria traceability |
| T7 | IMPLEMENT | [T2] | HIGH | Implement: Hot-link caching |
| T8 | TEST | [T7, T4] | MEDIUM | Unit and integration tests |
| T9 | TEST | [T7] | MEDIUM | Regression suite on impacted modules |
| T10 | DOCS | [T7] | LOW | API docs, runbook and changelog |
| T11 | RELEASE | [T8, T3, T9, T10] | HIGH | Release readiness review and human sign-off |

**Execution waves (parallelisable groups):** [[T1], [T2, T3], [T4, T7], [T8, T9, T10], [T11]]

## 3. Codebase impact (brownfield)
Impacted 4 file(s) (1 modified, 3 regression-review); 2 requested capabilities already exist

- `src/main/java/com/example/shortener/config/ShortenerConfiguration.java` [config] modify: declares ShortenerConfiguration; uses ShortUrlStore
- `src/main/java/com/example/shortener/persistence/JpaShortUrlStore.java` [persistence] review: uses ShortUrlStore
- `src/main/java/com/example/shortener/service/ShortUrlService.java` [service] review: uses ShortUrlStore
- `src/main/java/com/example/shortener/service/ShortUrlStore.java` [service] review: declares ShortUrlStore

**Already satisfied by existing code**
- Rate limiting: already implemented (TokenBucketRateLimiter in src/main/java/com/example/shortener/service/TokenBucketRateLimiter.java)
- Unsafe target protection: already implemented (isLocalOrPrivate in src/main/java/com/example/shortener/service/UrlValidator.java)

**Data flows**
- Redirect lookup path: RedirectController -> ShortUrlService.resolve -> ShortUrlStore.findByCode

## 4. Design
Incremental change to the existing layered service touching 4 file(s). Features: [Hot-link caching]

**Decisions**
- ADR: cache as a decorator on the ShortUrlStore port - zero change to business rules, trivially removable
- Trade-off: In-process cache: no new infrastructure and sub-ms hits, but per-instance and eventually consistent across replicas
- Backward compatibility: only optional request fields and additive columns; no endpoint is removed or renamed

## 5. Artifacts
| Type | Path | sha256 | Plan | Producer |
|---|---|---|---|---|
| API_SPEC | `docs/api/openapi.yaml` | 594861a1582b | v2 | design_gate |
| ADR | `docs/adr/ADR-37b4c4ab-design.md` | 230cf866c318 | v2 | design_gate |
| DOC | `docs/test-plan.md` | 9a6025daa7e0 | v2 | design_gate |
| CODE | `src/main/java/com/example/shortener/persistence/CachingShortUrlStore.java` | d89526f62ff6 | v2 | implementation |
| CODE | `src/main/java/com/example/shortener/config/ShortenerConfiguration.java` | d5799da0cf42 | v2 | implementation |
| TEST | `src/test/java/com/example/shortener/persistence/CachingShortUrlStoreTest.java` | 8992b92b686e | v2 | implementation |
| DOC | `docs/CHANGE-SUMMARY.md` | 7027f662f03e | v2 | documentation |
| DOC | `docs/runbook.md` | 9060a7e1de4d | v2 | documentation |
| DOC | `docs/CHANGELOG.md` | 7c1652f1fdf4 | v2 | documentation |

## 6. Validation
Attempt 1: **PASSED** (9 rules, 6 artifacts)


Rules: [SEC-001 No hard-coded secrets or keys in source, SEC-002 SQL must be parameterised (no string concatenation), SEC-003 No process execution or Java native deserialisation, CMP-001 No personal data (IP, e-mail, credentials) in logs, CHG-001 Writes restricted to src/, docs/ (no traversal, no absolute paths), CHG-002 Existing migrations are immutable; new migrations must be additive, CHG-003 Brownfield changes stay within the analysed impact set, QA-001 Acceptance criteria traced to tests; code changes include tests, QA-002 Generated Java is structurally complete]

**Release readiness:** GO - awaiting human release approval (score 100)
- Policy validation passed (no blocker/major)
- All acceptance criteria traced to test cases
- Code changes ship with tests
- Every HIGH/CRITICAL risk has a mitigation
- Runbook with rollback procedure present
- Artifact lineage consistent with current plan v2 (no stale outputs)
- High-risk design carries a human approval

## 7. Risks, trade-offs, failure scenarios (overall HIGH)
- **R-1 HIGH consistency** Stale reads: with several replicas a deactivated link can still redirect from another instance's cache until the TTL elapses -> _Short TTL (30s) + invalidate-on-write locally; pub/sub invalidation before scaling out_
- **R-2 MEDIUM capacity** Unbounded cache growth under link-scanning traffic -> _Bounded size with LRU eviction; only successful lookups are cached_
- **R-3 MEDIUM regression** Change touches 4 existing file(s) on the redirect/create paths -> _Regression suite on impacted modules; deploy behind canary; one-click rollback to previous build_

**Trade-offs**
- In-process cache: no new infrastructure and sub-ms hits, but per-instance and eventually consistent across replicas

**Failure scenarios**
- Database unavailable -> redirects fail with 5xx; readiness probe removes the instance; alert on error rate
- Random-code collision storm (keyspace exhaustion) -> bounded retry then 500; alert and raise code length
- Flyway migration fails at startup -> instance does not start (fail fast); previous version keeps serving
- Cache serves a link deactivated on another replica until TTL (30s) expires

## 8. Human checkpoints
- clarification_gate: **APPROVE** by tech-lead at 2026-09-27T12:51:53.549561373Z - "answers inline"
- design_approval: **APPROVE** by eng-manager at 2026-09-27T12:51:53.605155784Z - "accept up to 30s cross-replica staleness"
- release_approval: **APPROVE** by release-manager at 2026-09-27T12:51:53.675742093Z - "ship"

## 9. Decision lineage
1. **requirements_analysis** (agent:requirements_analysis/deterministic): 2 blocking ambiguities -> human clarification required - _features=[Unsafe target protection, Hot-link caching]; clarifications applied=[]_
2. **clarification_gate** (human:tech-lead): APPROVE - _answers inline_
3. **requirements_analysis** (agent:requirements_analysis/deterministic): Requirement is actionable (BROWNFIELD, 4 acceptance criteria) - _features=[Rate limiting, Unsafe target protection, Hot-link caching]; clarifications applied=[AMB-SECURE, AMB-FAST]_
4. **planning** (agent:planning/deterministic): 11 tasks in 5 waves (initial plan) - _Brownfield: analysis gates design; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review._
5. **codebase_analysis** (agent:codebase_analysis/deterministic): Blast radius 4 files; schema changes 0; existing capabilities 2 - _Impacted 4 file(s) (1 modified, 3 regression-review); 2 requested capabilities already exist_
6. **codebase_analysis** (governance:codebase_analysis): Re-planned to v2 after impact analysis - _schemaChanges=[], alreadySatisfied=[Rate limiting, Unsafe target protection]_
7. **architecture_design** (agent:architecture_design/deterministic): 3 design decisions - _Incremental change to the existing layered service touching 4 file(s). Features: [Hot-link caching]_
8. **risk_assessment** (agent:risk_assessment/deterministic): Overall risk HIGH (3 risks) - _floor=MEDIUM_
9. **test_strategy** (agent:test_strategy/deterministic): 12 test cases covering 4 acceptance criteria - _Test pyramid: fast deterministic unit tests on the framework-free domain, a thin layer of HTTP integration tests, explicit abuse cases; every acceptance criterion is traced to >= 1 case._
10. **design_gate** (governance:design_gate): Escalate design to human approval - _overall risk HIGH >= threshold HIGH; baseline frozen with 3 artifacts_
11. **design_approval** (human:eng-manager): APPROVE - _accept up to 30s cross-replica staleness_
12. **implementation** (agent:implementation/deterministic): Attempt 1: 2 code, 1 test, 0 other files - _Rate limiting: already implemented, no change; Unsafe target protection: already implemented, no change; Hot-link caching: 3 files via verified template_
13. **validation** (governance:validation): Validation attempt 1 PASSED (0 minor findings) - _9 rules over 6 artifacts_
14. **documentation** (agent:documentation/deterministic): 3 documents
15. **release_readiness** (governance:release_readiness): GO - awaiting human release approval - _score 100/100_
16. **release_approval** (human:release-manager): APPROVE - _ship_
17. **publish** (governance:publish): Published 9 artifacts - _approved by release-manager; manifest with sha256 + lineage written_

**Execution path:** 01:requirements_analysis -> 02:clarification_gate -> 03:requirements_analysis -> 04:planning -> 05:codebase_analysis -> 06:architecture_design -> 06:risk_assessment -> 06:test_strategy -> 09:design_gate -> 10:design_approval -> 11:implementation -> 12:validation -> 13:documentation -> 14:release_readiness -> 15:release_approval -> 16:publish

**Control loop:** implementation attempts=1, re-plans=1, plan version=v2

## 10. Assumptions and limitations
- Java 21 / Spring Boot 3 service; H2 for local runs, PostgreSQL-compatible SQL for production
- Single region deployment; management API authentication is handled by the API gateway
- Changes must be backward compatible for existing clients and stored links
- Validation is static (policy rules + structural checks); generated code is compiled and tested by CI after publish, not inside the graph.
- Offline mode uses curated templates for known features; unknown features produce change proposals instead of speculative code.
- Checkpoints are in memory: a restart loses paused runs (swap MemorySaver for a persistent saver in production).
