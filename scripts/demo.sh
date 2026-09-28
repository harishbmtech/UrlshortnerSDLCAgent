#!/usr/bin/env bash
# End-to-end demo against a running instance (./mvnw spring-boot:run, Ollama optional). Requires curl and jq.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
J='Content-Type: application/json'

say() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
status() { curl -s "$BASE/api/v1/sdlc/runs/$1" | jq -r '"status=\(.status) gate=\(.pendingGate) planV=\(.planVersion) attempts=\(.implementationAttempts)"'; }
decide() { # runId gate verdict approver comment [clarificationsJson]
  local clar=${6:-'{}'}
  curl -s -X POST "$BASE/api/v1/sdlc/runs/$1/decisions" -H "$J" \
    -d "{\"gate\":\"$2\",\"verdict\":\"$3\",\"approver\":\"$4\",\"comment\":\"$5\",\"clarifications\":$clar}" \
    | jq -r '"-> status=\(.status) gate=\(.pendingGate)"'
}
FAST="p95 redirect latency under 20 ms at 500 requests/second by caching hot links in-process"
SECURE="block private-network (SSRF) targets and rate-limit link creation"
# Plays the human roles until the run finishes. Works whatever gates a live Ollama model leads to, and
# answers clarifications using the ambiguity ids the requirements agent actually produced.
drive() { # runId [stop-at-release]
  for _ in 1 2 3 4 5 6; do
    local run gate
    run=$(curl -s "$BASE/api/v1/sdlc/runs/$1")
    gate=$(jq -r '.pendingGate // empty' <<<"$run")
    [ -z "$gate" ] && break
    [ "$gate" = release_approval ] && [ -n "${2:-}" ] && break
    case "$gate" in
      clarification_gate)
        clar=$(jq -c --arg f "$FAST" --arg s "$SECURE" '[.spec.ambiguities[] | {key: .id, value: (
          ((.id + " " + (.statement // "") + " " + (.question // "")) | ascii_downcase) as $t
          | if ($t|test("fast|latency|perform")) and ($t|test("secur")|not) then $f
            elif ($t|test("secur")) and ($t|test("fast|latency|perform")|not) then $s else ($f + "; " + $s) end)}] | from_entries' <<<"$run")
        echo "clarifications: $clar"
        decide "$1" clarification_gate APPROVE tech-lead "answers" "$clar" ;;
      design_approval)  decide "$1" design_approval APPROVE eng-manager "accept 30s staleness" ;;
      release_approval) decide "$1" release_approval APPROVE release-manager "ship it" ;;
      *) echo "unexpected gate $gate"; break ;;
    esac
  done
}
start() { curl -s -X POST "$BASE/api/v1/sdlc/runs" -H "$J" -d "$1" | jq -r .runId; }

say "Multi-agent roster: which Ollama model serves each agent"
curl -s "$BASE/api/v1/sdlc/agents" | jq '{mode, ollama: {reachable: .ollama.reachable, missing: .ollama.missingModels}, agents: [.agents[] | "\(.agent) -> \(.model)"]}'

say "0. The URL shortener itself"
curl -s -X POST "$BASE/api/v1/urls" -H "$J" -d '{"url":"https://spring.io/projects/spring-ai","alias":"springai"}' | jq .
curl -s -o /dev/null -w 'GET /springai -> %{http_code} Location: %{redirect_url}\n' "$BASE/springai"
curl -s "$BASE/api/v1/urls/springai/stats" | jq .

say "1. GREENFIELD"
G=$(start '{"requirement":"Build a new URL shortener service from scratch: create short links via a REST API, redirect by code, click analytics, optional link expiry and custom aliases."}')
echo "run $G: $(status "$G")"
drive "$G"

say "2. BROWNFIELD (with one injected policy violation to show retry-with-feedback)"
B=$(start '{"requirement":"Add an optional max-clicks limit to existing short links: once a link has been followed N times it must stop redirecting and return 410 Gone.","faults":{"policy.implementation":1}}')
echo "run $B: $(status "$B")"
curl -s "$BASE/api/v1/sdlc/runs/$B" | jq '{impact: .impact.components, schema: .impact.schemaChanges, validationAttempt: .validation.attempt}'
drive "$B" stop-at-release
echo "reviewer rejects -> dynamic re-plan:"
decide "$B" release_approval REJECT release-manager "Also return maxClicks in GET /api/v1/urls/{code}"
drive "$B"

say "3. AMBIGUOUS"
A=$(start '{"requirement":"Make the short links faster and more secure."}')
echo "run $A: $(status "$A")"
curl -s "$BASE/api/v1/sdlc/runs/$A" | jq '.spec.ambiguities'
drive "$A"
curl -s "$BASE/api/v1/sdlc/runs/$A" | jq '{status, risk: .risk.overall, alreadyExists: .impact.existingCapabilities}'

say "4. Governance evidence"
curl -s "$BASE/api/v1/sdlc/runs/$A/audit" | jq '{chainVerified, events: (.events | length)}'
curl -s "$BASE/api/v1/sdlc/metrics" | jq 'del(.perNode)'
echo; echo "Summaries: $BASE/api/v1/sdlc/runs/{$G,$B,$A}/summary"
echo "Graph (mermaid): $BASE/api/v1/sdlc/graph"
