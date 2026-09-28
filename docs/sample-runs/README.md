# Sample runs (real output)

These files were produced by running the actual LangGraph4j graph in deterministic mode (`OLLAMA_ENABLED=false`), so they are reproducible. With Ollama on, the same graph runs and each decision names its model, e.g. `agent:implementation/llm[ollama:qwen2.5-coder:7b]`. The human decisions were scripted: a clarification, a design approval, one release rejection and the release approvals.

| File | What it shows |
|---|---|
| `1-greenfield-ENGINEERING-SUMMARY.md` | Greenfield: parallel design branches, design auto-approved (MEDIUM risk), release approval, publish |
| `2-brownfield-ENGINEERING-SUMMARY.md` | Codebase impact analysis, re-plan for the schema change, an injected policy violation followed by retry with feedback, a reviewer **rejection** followed by a dynamic re-plan and re-approval |
| `2-brownfield-ShortUrl.java`, `2-brownfield-MaxClicksLimitTest.java` | Generated brownfield patch. Applied to the repo, it compiles and all 19 shortener tests pass |
| `3-ambiguous-ENGINEERING-SUMMARY.md` | Clarification gate, capabilities that already exist are detected (and their tasks dropped), HIGH-risk design escalated to a human |
| `3-ambiguous-CachingShortUrlStore.java` | Generated cache decorator (with its test, it passes against the existing suite) |
| `3-ambiguous-audit.jsonl` | Hash-chained audit trail of the ambiguous run (every node, the interrupt, the human decisions) |
| `4-rollback-safe-stop-ENGINEERING-SUMMARY.md` | Persistent policy violations: 3 bounded attempts, then rollback to the design baseline, then safe-stop |
| `reliability-metrics.json` | Orchestrator reliability metrics across these 4 runs |
