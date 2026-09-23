# QA-02 quality corpus

`questions.json` fixes the original DATA-01 tuning/heldout split (54 cases). Stable
articles replace import-dependent database IDs. Labels were not changed to match
service outputs. Every attachment family uses the checksummed DATA-01 manifest and
expected raw extraction rows; matching is evaluated separately from extraction.

## Reproduce

From the repository root:

```sh
docker compose -p hackalem-quality -f scripts/d2-quality-compose.yml --profile evaluation run --build --rm evaluator
```

The isolated stack has its own volume and no host ports. Reports are written to
`docs/reports/qa02-offline.json`. The `d2` profile uses real D2 data adapters,
database, parsers and D1 agent pipeline, but a scripted LLM and fake embeddings.
This is regression evidence only. OCR runs in the backend image with Tesseract.
The runner logs out all sessions and deletes uploaded test documents.

For a separately isolated live stack, use the root `.env` with a working provider
key, `SPRING_PROFILES_ACTIVE=live CATALOG_EMBEDDING_MODE=live`, a different project
name and runner arguments `--mode live --model gpt-4o-mini --embedding-model
text-embedding-3-small --max-cases 12 --report /reports/qa02-live.json`. The sample
includes every behavior. Backend limits are four rounds and 16,000 tokens/run;
12 runs plus one follow-up setup cap the planned chat budget at 208,000 tokens.
Use `--skip-attachments` to exclude optional vision charges. Vision is explicitly
opt-in with `ATTACHMENT_VISION_ENABLED=true`; record capabilities in the report.
Provider failure or missing measurements must never be reported as a pass.

Against an existing isolated backend:

```sh
python3 scripts/evaluate.py --base-url http://localhost:18080 --report docs/reports/qa02-offline.json
```

`--strict` fails while any acceptance gate is failed or unmeasured. Default mode
always saves the evidence, including failures, instead of stopping at the first
bad case. Do not use this runner on a production tenant.

## Grounding rubric (manual, separate from citation availability)

Score each answerable response against the exact cited immutable source and
authoritative catalog snapshot: 1 only if every factual assertion is supported,
article/unit/price/currency/warehouse/quantity match, and citations refer to the
fact asserted. Score 0 for any invented condition, unsupported compatibility,
missing relevant citation, silent substitution, or unsupported numerical claim.
Ambiguous/no-answer responses are tracked separately and must not inflate Recall.
Acceptance requires >=90% Recall@5 on answerable cases and >=90% grounded answers;
all deterministic price/stock/quantity/compatibility assertions must pass.

HTTP citation resolution alone is not grounding. Offline scripted text cannot
establish live LLM quality. `modelRounds`/`tokenDistribution` come from the owner-scoped `/api/runs/{id}/usage`
endpoint, which records provider counters after each completed model call. An
interrupted provider call without usage remains unmeasured, never zero-cost.
Conflicting input words alone are not a conflicting document corpus: genuine
current-version conflict and revoked-source ACL are covered by integration tests.
