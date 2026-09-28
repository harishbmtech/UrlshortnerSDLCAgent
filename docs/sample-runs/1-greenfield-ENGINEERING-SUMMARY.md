# Engineering summary - run 8bd86c70-5c27-4e89-a4a2-36eb12db98a2

**Outcome:** COMPLETED  
**Published to:** `build/sdlc-output/8bd86c70-5c27-4e89-a4a2-36eb12db98a2`

## 1. Requirement understanding
> Build a new URL shortener service from scratch: create short links via a REST API, redirect by code, click analytics, optional link expiry and custom aliases.

- **Classification:** GREENFIELD
- **Features:** [Create short links, Redirect, Click analytics, Link expiry, Custom aliases]

**Acceptance criteria**
- AC-1: POST /api/v1/urls with a valid http(s) URL returns 201, a unique 7-character Base62 code and a Location header
- AC-2: Invalid or unsafe target URLs are rejected with 400 and an RFC 7807 problem body
- AC-3: GET /{code} responds 302 to the target with Cache-Control: no-store; unknown codes return 404
- AC-4: Every redirect increments the click counter atomically and GET /api/v1/urls/{code}/stats returns total, last-24h and top referrers without storing raw client IPs
- AC-5: Links created with ttlSeconds stop resolving once expired and return 410 Gone; TTLs above the configured maximum are rejected
- AC-6: A custom alias (4-32 chars of [A-Za-z0-9_-]) is honoured; a taken alias returns 409 and reserved words are rejected

**Non-functional**
- Stateless application instances so the service scales horizontally behind a load balancer
- No raw personal data (client IPs) persisted or logged
- Schema managed by versioned, reviewable migrations

## 2. Plan v1 and rationale
Greenfield: contract-first; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review.

| Task | Stage | Depends on | Impact | Title |
|---|---|---|---|---|
| T1 | DESIGN | [] | MEDIUM | Design API contract and schema changes |
| T2 | DESIGN | [] | MEDIUM | Threat model and risk assessment |
| T3 | TEST | [T1] | LOW | Test strategy with acceptance-criteria traceability |
| T4 | IMPLEMENT | [T1] | MEDIUM | Implement: Create short links |
| T5 | IMPLEMENT | [T1] | MEDIUM | Implement: Redirect |
| T6 | IMPLEMENT | [T1] | MEDIUM | Implement: Click analytics |
| T7 | IMPLEMENT | [T1] | LOW | Implement: Link expiry |
| T8 | IMPLEMENT | [T1] | LOW | Implement: Custom aliases |
| T9 | TEST | [T4, T5, T6, T7, T8, T3] | MEDIUM | Unit and integration tests |
| T10 | DOCS | [T4, T5, T6, T7, T8] | LOW | API docs, runbook and changelog |
| T11 | RELEASE | [T9, T2, T10] | HIGH | Release readiness review and human sign-off |

**Execution waves (parallelisable groups):** [[T1, T2], [T3, T4, T5, T6, T7, T8], [T9, T10], [T11]]

## 4. Design
Layered hexagonal service: REST controllers -> framework-free domain services -> ShortUrlStore port -> JPA adapter; Flyway-managed schema; stateless instances. Features: [Create short links, Redirect, Click analytics, Link expiry, Custom aliases]

**Decisions**
- ADR: hexagonal core - domain logic behind a ShortUrlStore port so storage can change without touching rules
- ADR: Flyway owns the schema; ddl-auto=none
- Trade-off: Random codes (non-enumerable) over Base62(id): needs a uniqueness check + retry, but no scraping
- ADR: 302 + no-store instead of 301 to keep analytics and revocation accurate
- Trade-off: 302 (not 301) so every click is counted and disabling a link is immediate, at the cost of an extra hop for repeat visitors
- ADR: atomic SQL increment for counters; raw events kept for time-window analytics
- Trade-off: Synchronous click recording keeps the design simple but adds a write to the hot path; move to an async queue at high RPS
- ADR: 410 Gone (not 404) for expired/disabled links so clients can tell 'existed' from 'never existed'
- Trade-off: Lazy expiry on read (no background job) - simple, but expired rows stay until a cleanup job is added

## 5. Artifacts
| Type | Path | sha256 | Plan | Producer |
|---|---|---|---|---|
| API_SPEC | `docs/api/openapi.yaml` | 9a9806b557e3 | v1 | design_gate |
| SCHEMA | `docs/design/schema-change.sql` | 6678326f97d4 | v1 | design_gate |
| ADR | `docs/adr/ADR-8bd86c70-design.md` | 2eb54556f0ad | v1 | design_gate |
| DOC | `docs/test-plan.md` | 9746d19ad43b | v1 | design_gate |
| CODE | `src/main/java/com/example/shortener/config/ShortenerConfiguration.java` | fa7558982556 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/config/ShortenerProperties.java` | 06e5f34c6ba0 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/domain/ClickEvent.java` | 1112aab8f52d | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/domain/LinkStats.java` | c95ba4a4eef7 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/domain/ShortUrl.java` | 110171935158 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/domain/ShortenerExceptions.java` | 8cb8a6af869c | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/persistence/ClickEventJpaRepository.java` | 4a321e884b5e | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/persistence/JpaShortUrlStore.java` | dcf4579d4b9f | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/persistence/ShortUrlJpaRepository.java` | c994037c5099 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/service/CodeGenerator.java` | afb975803d48 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/service/ShortUrlService.java` | c4cce397bd14 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/service/ShortUrlStore.java` | 3c504ed75b2f | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/service/TokenBucketRateLimiter.java` | ad1ab481e816 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/service/UrlValidator.java` | 6dd29552e681 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/web/GlobalExceptionHandler.java` | 1bc0edfd8ba3 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/web/RedirectController.java` | 0df72f4be3c4 | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/web/ShortUrlController.java` | 2ca286b1042d | v1 | implementation |
| CODE | `src/main/java/com/example/shortener/web/ShortUrlDtos.java` | cd965643a604 | v1 | implementation |
| TEST | `src/test/java/com/example/shortener/service/ShortUrlServiceTest.java` | 783cf2823f0b | v1 | implementation |
| TEST | `src/test/java/com/example/shortener/service/TokenBucketRateLimiterTest.java` | d5ec2a1ffa36 | v1 | implementation |
| TEST | `src/test/java/com/example/shortener/service/UrlValidatorTest.java` | f9305c82735c | v1 | implementation |
| TEST | `src/test/java/com/example/shortener/support/InMemoryShortUrlStore.java` | b6e1f474b47f | v1 | implementation |
| TEST | `src/test/java/com/example/shortener/support/MutableClock.java` | 64d5965c98b1 | v1 | implementation |
| MIGRATION | `src/main/resources/db/migration/V1__create_short_url_tables.sql` | d2818d37df0f | v1 | implementation |
| DOC | `docs/CHANGE-SUMMARY.md` | 9eabf4f723ad | v1 | documentation |
| DOC | `docs/runbook.md` | ae6072996260 | v1 | documentation |
| DOC | `docs/CHANGELOG.md` | 235e550bf864 | v1 | documentation |

## 6. Validation
Attempt 1: **PASSED** (9 rules, 28 artifacts)


Rules: [SEC-001 No hard-coded secrets or keys in source, SEC-002 SQL must be parameterised (no string concatenation), SEC-003 No process execution or Java native deserialisation, CMP-001 No personal data (IP, e-mail, credentials) in logs, CHG-001 Writes restricted to src/, docs/ (no traversal, no absolute paths), CHG-002 Existing migrations are immutable; new migrations must be additive, CHG-003 Brownfield changes stay within the analysed impact set, QA-001 Acceptance criteria traced to tests; code changes include tests, QA-002 Generated Java is structurally complete]

**Release readiness:** GO - awaiting human release approval (score 100)
- Policy validation passed (no blocker/major)
- All acceptance criteria traced to test cases
- Code changes ship with tests
- Every HIGH/CRITICAL risk has a mitigation
- Runbook with rollback procedure present
- Artifact lineage consistent with current plan v1 (no stale outputs)
- Design within autonomy boundary (no human design approval required)

## 7. Risks, trade-offs, failure scenarios (overall MEDIUM)
- **R-1 MEDIUM security** Sequential codes would let anyone enumerate every stored link -> _Random 7-char Base62 codes from SecureRandom (62^7 keyspace) with bounded collision retry_
- **R-2 MEDIUM abuse** Service used to disguise phishing/malware targets -> _Scheme allow-list, credential/private-host rejection, configurable domain deny-list_
- **R-3 MEDIUM availability** Redirect path depends on the database; a DB outage breaks every link -> _Health checks + connection pool limits; hot-link cache as a follow-up_
- **R-4 MEDIUM privacy** Client IPs are personal data (GDPR) -> _Store only a salted SHA-256 prefix of the IP; never log IPs_
- **R-5 MEDIUM consistency** Read-modify-write counter loses updates under concurrent redirects -> _Single-statement UPDATE ... SET click_count = click_count + 1_
- **R-6 LOW correctness** Clock skew between instances shifts expiry by the skew -> _NTP-synchronised hosts; expiry evaluated against a single injected Clock_
- **R-7 LOW security** Aliases could shadow API or infrastructure routes -> _Reserved-word list (api, actuator, admin...) and strict character set_

**Trade-offs**
- Random codes (non-enumerable) over Base62(id): needs a uniqueness check + retry, but no scraping
- 302 (not 301) so every click is counted and disabling a link is immediate, at the cost of an extra hop for repeat visitors
- Synchronous click recording keeps the design simple but adds a write to the hot path; move to an async queue at high RPS
- Lazy expiry on read (no background job) - simple, but expired rows stay until a cleanup job is added

**Failure scenarios**
- Database unavailable -> redirects fail with 5xx; readiness probe removes the instance; alert on error rate
- Random-code collision storm (keyspace exhaustion) -> bounded retry then 500; alert and raise code length
- Flyway migration fails at startup -> instance does not start (fail fast); previous version keeps serving

## 8. Human checkpoints
- release_approval: **APPROVE** by release-manager at 2026-09-27T12:51:52.941142708Z - "ship it"

## 9. Decision lineage
1. **requirements_analysis** (agent:requirements_analysis/deterministic): Requirement is actionable (GREENFIELD, 6 acceptance criteria) - _features=[Create short links, Redirect, Click analytics, Link expiry, Custom aliases]; clarifications applied=[]_
2. **planning** (agent:planning/deterministic): 11 tasks in 4 waves (initial plan) - _Greenfield: contract-first; design and threat modelling run in parallel; implementation tasks per feature run in parallel; release is gated on tests, docs and risk review._
3. **codebase_analysis** (governance:codebase_analysis): Skipped impact analysis - _greenfield_
4. **architecture_design** (agent:architecture_design/deterministic): 9 design decisions - _Layered hexagonal service: REST controllers -> framework-free domain services -> ShortUrlStore port -> JPA adapter; Flyway-managed schema; stateless instances. Features: [Create short links, Redirect, Click analytics, Link expiry, Custom aliases]_
5. **risk_assessment** (agent:risk_assessment/deterministic): Overall risk MEDIUM (7 risks) - _floor=LOW_
6. **test_strategy** (agent:test_strategy/deterministic): 16 test cases covering 6 acceptance criteria - _Test pyramid: fast deterministic unit tests on the framework-free domain, a thin layer of HTTP integration tests, explicit abuse cases; every acceptance criterion is traced to >= 1 case._
7. **design_gate** (governance:design_gate): Design auto-approved within autonomy boundary - _overall risk MEDIUM < threshold HIGH; baseline frozen with 4 artifacts_
8. **implementation** (agent:implementation/deterministic): Attempt 1: 18 code, 5 test, 1 other files - _greenfield: replayed curated reference implementation (24 files)_
9. **validation** (governance:validation): Validation attempt 1 PASSED (0 minor findings) - _9 rules over 28 artifacts_
10. **documentation** (agent:documentation/deterministic): 3 documents
11. **release_readiness** (governance:release_readiness): GO - awaiting human release approval - _score 100/100_
12. **release_approval** (human:release-manager): APPROVE - _ship it_
13. **publish** (governance:publish): Published 31 artifacts - _approved by release-manager; manifest with sha256 + lineage written_

**Execution path:** 01:requirements_analysis -> 02:planning -> 03:codebase_analysis -> 04:architecture_design -> 04:risk_assessment -> 04:test_strategy -> 07:design_gate -> 08:implementation -> 09:validation -> 10:documentation -> 11:release_readiness -> 12:release_approval -> 13:publish

**Control loop:** implementation attempts=1, re-plans=0, plan version=v1

## 10. Assumptions and limitations
- Java 21 / Spring Boot 3 service; H2 for local runs, PostgreSQL-compatible SQL for production
- Single region deployment; management API authentication is handled by the API gateway
- Validation is static (policy rules + structural checks); generated code is compiled and tested by CI after publish, not inside the graph.
- Offline mode uses curated templates for known features; unknown features produce change proposals instead of speculative code.
- Checkpoints are in memory: a restart loses paused runs (swap MemorySaver for a persistent saver in production).
