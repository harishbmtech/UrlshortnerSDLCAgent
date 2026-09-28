#!/usr/bin/env bash
# Checks the local Ollama server and pulls the models the agents use.
#   ./scripts/ollama-setup.sh
# Override models with OLLAMA_REASONING_MODEL / OLLAMA_CODER_MODEL, and the server with OLLAMA_BASE_URL.
set -euo pipefail
BASE=${OLLAMA_BASE_URL:-http://localhost:11434}
REASONING=${OLLAMA_REASONING_MODEL:-llama3.1:8b}
CODER=${OLLAMA_CODER_MODEL:-qwen2.5-coder:7b}

if ! command -v ollama >/dev/null 2>&1; then
  echo "Ollama CLI not found. Install it from https://ollama.com/download (or use: docker compose up)."; exit 1
fi
if ! curl -sf "$BASE/api/tags" >/dev/null; then
  echo "Ollama is not answering at $BASE. Start it with: ollama serve"; exit 1
fi

for m in "$REASONING" "$CODER"; do
  if ollama list | awk 'NR>1 {print $1}' | grep -qx "$m"; then
    echo "ok      $m"
  else
    echo "pulling $m"; ollama pull "$m"
  fi
done

cat <<EOF

Agent -> model roster (application.yml, sdlc.llm.agent-models):
  requirements_analysis, planning, architecture_design, risk_assessment, documentation -> $REASONING
  codebase_analysis, test_strategy, implementation                                     -> $CODER

Smoke test of the coder model with JSON output:
EOF
curl -s "$BASE/api/chat" -d "{\"model\":\"$CODER\",\"stream\":false,\"format\":\"json\",
  \"messages\":[{\"role\":\"user\",\"content\":\"Return {\\\"ok\\\": true} as JSON.\"}]}" | head -c 300; echo
echo; echo "Now run: ./mvnw spring-boot:run   then: curl localhost:8080/api/v1/sdlc/agents"
