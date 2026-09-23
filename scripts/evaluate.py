#!/usr/bin/env python3
"""QA-02: real HTTP agent/extraction evaluation. No fixtures replace service responses.

Offline baseline and live provider runs produce separate reports; missing evidence fails
its gate. Provider credentials are read by the backend, never by this runner.
"""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import time
from urllib.error import HTTPError
from urllib.request import Request
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("smoke", ROOT / "scripts/d1-smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def load(path):
    return json.loads((ROOT / path).read_text())


def percentile(values, fraction):
    if not values:
        return None
    return sorted(values)[min(len(values) - 1, int((len(values) - 1) * fraction))]


def distribution(values):
    return {"count": len(values), "p50": percentile(values, .5),
            "p95": percentile(values, .95), "max": max(values) if values else None}


def wait_for(client, path, token, terminal, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        result = client.request("GET", path, token)
        if result["status"].lower() in terminal:
            return result
        if time.monotonic() >= deadline:
            raise smoke.SmokeFailure("Job deadline exceeded")
        time.sleep(.2)


def turn(client, token, conversation, text):
    accepted = client.request("POST", f"/api/conversations/{conversation}/messages", token,
                              {"text": text}, "eval-" + uuid4().hex, expected=(202,))
    path = "/api/runs/" + accepted["runId"]
    run = wait_for(client, path, token, {"completed", "failed", "cancelled"})
    events = client.request("GET", path + "/events", token, sse=True)
    return run, events


def evaluate_case(base_url, case):
    client = smoke.Client(base_url)
    start = time.monotonic()
    result = {"id": case["id"], "split": case["split"], "kind": case["kind"],
              "query": case["query"], "expectedArticles": case["expectedArticles"],
              "recallAt5": 0.0 if case["expectedArticles"] else None}
    token = None
    try:
        token = client.request("POST", "/auth/visitor-session")["accessToken"]
        conversation = client.request("POST", "/api/conversations", token, expected=(201,))["id"]
        for text in case.get("setupQueries", []):
            turn(client, token, conversation, text)
        before = client.request("GET", f"/api/conversations/{conversation}/state", token)
        run, events = turn(client, token, conversation, case["query"])
        result["usage"] = client.request("GET", "/api/runs/" + run["id"] + "/usage", token)
        sets = [e["payload"]["resultSet"] for e in events if e["type"] == "products.result"]
        sources = [s for e in events if e["payload"].get("kind") == "sources"
                   for s in e["payload"].get("sources", [])]
        excerpts = [s for e in events if e["payload"].get("kind") == "sources"
                    for s in e["payload"].get("excerpts", [])]
        articles = [p["article"] for rs in sets for p in rs["products"]][:5]
        result.update(status=run["status"], errorCode=run.get("errorCode"),
                      answer=run["text"], articles=articles, sources=sources, excerpts=excerpts,
                      toolCalls=sum(e["type"] == "tool.status" and e["payload"].get("status") == "running" for e in events),
                      runId=run["id"], offers=[o for rs in sets for o in rs["offers"]])
        expected = set(case["expectedArticles"])
        result["recallAt5"] = len(expected.intersection(articles)) / len(expected) if expected else None
        source_checks = []
        for source in sources:
            try:
                request = Request(client.base_url + f'/api/sources/{source["id"]}/versions/{source["version"]}', headers={"Authorization": "Bearer " + token})
                with client.opener.open(request, timeout=15) as response:
                    smoke.check(bool(response.read(100000)), "Empty cited source")
                source_checks.append({"id": source["id"], "version": source["version"], "resolved": True})
            except (smoke.SmokeFailure, HTTPError):
                source_checks.append({"id": source["id"], "version": source["version"], "resolved": False})
        result["citationResolution"] = source_checks
        if case["kind"] == "injection":
            result["noUnconfirmedMutation"] = client.request("GET", "/api/cart", token)["lines"] == []
        if case["kind"] == "compatibility":
            plans = client.request("GET", "/api/products/000001/analogs?quantity=20&unit=pcs", token)
            options = plans.get("options", [])
            lines = [{line["article"]: line["addQuantity"] for line in option["lines"]} for option in options]
            result["fulfillment"] = {"split12And8": {"000001": "12", "000002": "8"} in lines,
                                     "whole20": {"000003": "20"} in lines,
                                     "noUnverifiedAnalogs": all(a["compatibility"]["status"] == "VERIFIED" for a in plans.get("analogs", [])),
                                     "noUnconfirmedMutation": client.request("GET", "/api/cart", token)["lines"] == []}
            result["fulfillmentOptions"] = options
        if case["kind"] == "followup":
            after = client.request("GET", f"/api/conversations/{conversation}/state", token)
            result["sameSavedResultSet"] = bool(before["lastResultSetId"]) and before["lastResultSetId"] == after["lastResultSetId"]
        if case.get("requiresConflictFixture"):
            result["conflictEvidence"] = "not_measured: conflicting-current-document fixture is covered by backend integration tests"
        if case["kind"] == "noanswer":
            result["noConfidentMatch"] = run["status"] == "completed" and not articles and not sources
    except Exception as error:
        result.update(status="failed", error=str(error)[:300])
    finally:
        if token:
            try:
                client.request("POST", "/auth/logout", token)
            except smoke.SmokeFailure:
                pass
    result["latencyMs"] = round((time.monotonic() - start) * 1000)
    return result


def upload(client, token, conversation, path):
    boundary = "qa-" + uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    req = Request(client.base_url + f"/api/conversations/{conversation}/attachments", data=body,
                  headers={"Authorization": "Bearer " + token, "Content-Type": f"multipart/form-data; boundary={boundary}"}, method="POST")
    try:
        response = client.opener.open(req, timeout=30)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read(2 * 1024 * 1024)
        return response.status, json.loads(raw) if raw else {}


def attachment_cases(base_url):
    client = smoke.Client(base_url)
    token = client.request("POST", "/auth/visitor-session")["accessToken"]
    conversation = client.request("POST", "/api/conversations", token, expected=(201,))["id"]
    results = []
    try:
        capabilities = client.request("GET", "/api/attachments/capabilities", token)
        for fixture in load("data/attachments/manifest.json")["files"]:
            item = {"file": fixture["file"], "family": fixture["family"], "expected": fixture["expected"]}
            start = time.monotonic()
            attachment_id = None
            try:
                path = ROOT / fixture["file"]
                smoke.check(hashlib.sha256(path.read_bytes()).hexdigest() == fixture["sha256"], "Fixture hash mismatch")
                status, accepted = upload(client, token, conversation, path)
                if status >= 400:
                    item.update(httpStatus=status, rejectedCorrectly=fixture["expected"] == "REJECT" and 400 <= status < 500, status="rejected")
                else:
                    attachment_id = accepted["attachmentId"]
                    detail = wait_for(client, "/api/attachments/" + attachment_id, token,
                                      {"ready", "needs_review", "reviewed", "failed", "rejected"})
                    rows = detail.get("rows", [])
                    item.update(status=detail["status"], rows=rows, selections=detail.get("selections", []))
                    expected = load(fixture["expectedRows"])["rows"] if fixture["expectedRows"] else []
                    expected_counts = Counter((r["article"], r["quantity"], r["unit"]) for r in expected)
                    expected_counts = Counter({k: v * fixture["expectedRowOccurrences"] for k, v in expected_counts.items()})
                    actual = Counter((r["extracted"].get("article"), r["extracted"].get("quantity"), r["extracted"].get("unit")) for r in rows)
                    item["expectedRows"] = sum(expected_counts.values())
                    item["correctExtractedRows"] = sum((actual & expected_counts).values())
                    item["extractionRecall"] = item["correctExtractedRows"] / item["expectedRows"] if expected_counts else None
                    item["noAutoSelection"] = not detail.get("selections")
                    item["falseConfidentMatches"] = sum(r.get("status") == "MATCHED" for r in rows) if not expected else 0
                    item["rejectedCorrectly"] = detail["status"].lower() in {"failed", "rejected"} if fixture["expected"] == "REJECT" else None
            except Exception as error:
                item.update(status="failed", error=str(error)[:300])
            finally:
                if attachment_id:
                    client.request("DELETE", "/api/attachments/" + attachment_id, token, expected=(200, 204))
            item["latencyMs"] = round((time.monotonic() - start) * 1000)
            results.append(item)
        return {"capabilities": capabilities, "files": results}
    finally:
        client.request("POST", "/auth/logout", token)


def summarize(cases, products):
    recall = {}
    for split in ("all", "tuning", "heldout"):
        values = [c["recallAt5"] for c in cases if c.get("recallAt5") is not None and (split == "all" or c["split"] == split)]
        recall[split] = statistics.mean(values) if values else None
    checks = []
    expected = {p["article"]: p for p in products}
    for case in cases:
        for offer in case.get("offers", []):
            product = expected.get(offer["article"])
            warehouse = next((w for w in product["warehouses"] if w["warehouseId"] == offer["warehouse"]), None) if product else None
            checks.append(bool(warehouse and warehouse["eligible"] and warehouse["status"] != "UNKNOWN"
                               and Decimal(offer["price"]["amount"]) == Decimal(product["price"])
                               and offer["price"]["currency"] == product["currency"]
                               and Decimal(offer["available"]["value"]) == Decimal(warehouse["availableQuantity"])))
    citations = [s["resolved"] for c in cases for s in c.get("citationResolution", [])]
    return {"recallAt5": recall, "completed": sum(c["status"] == "completed" for c in cases),
            "authoritativeOfferAssertions": {"checked": len(checks), "passed": sum(checks)},
            "citationResolution": {"checked": len(citations), "passed": sum(citations)},
            "latencyMs": distribution([c["latencyMs"] for c in cases]),
            "toolCalls": distribution([c.get("toolCalls", 0) for c in cases]),
            "modelRounds": distribution([len(c["usage"]) for c in cases if "usage" in c]),
            "tokenDistribution": distribution([sum(r["inputTokens"] + r["outputTokens"] for r in c["usage"]) for c in cases if c.get("usage") and all(r["measured"] for r in c["usage"])]),
            "groundedRubricScore": None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mode", choices=("offline", "live"), default="offline")
    parser.add_argument("--model", default="contract-scripted")
    parser.add_argument("--embedding-model", default="fake")
    parser.add_argument("--max-cases", type=int, default=54)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--skip-attachments", action="store_true")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--strict", action="store_true", help="exit 1 when any quality gate lacks evidence or fails")
    args = parser.parse_args()
    if args.mode == "live" and (args.max_cases > 12 or args.model == "contract-scripted" or args.embedding_model == "fake"):
        parser.error("Live run needs actual model identifiers and --max-cases <=12 (explicit budget)")
    dataset = load("data/evaluation/questions.json")
    # Bounded live sample includes every behavior, rather than only easy exact matches.
    live_ids = {"exact-04", "name-01", "typo", "terms-2", "terms-4", "terms-6", "missing", "warranty", "followup", "compatibility", "conflict", "injection"}
    selected = [c for c in dataset["cases"] if args.mode == "offline" or c["id"] in live_ids][:args.max_cases]
    with ThreadPoolExecutor(max_workers=max(1, min(args.concurrency, 4))) as executor:
        cases = list(executor.map(lambda c: evaluate_case(args.base_url, c), selected))
    summary = summarize(cases, load("data/sample_catalog/products.json")["products"])
    attachments = None if args.skip_attachments else attachment_cases(args.base_url)
    score = summary["recallAt5"]["all"]
    report = {"schemaVersion": 1, "mode": args.mode, "model": args.model, "embeddingModel": args.embedding_model,
              "promptVersion": "ekt-agent-v1", "corpusVersion": dataset["corpusVersion"], "datasetVersion": dataset["version"],
              "datasetSha256": hashlib.sha256((ROOT / "data/evaluation/questions.json").read_bytes()).hexdigest(),
              "summary": summary, "cases": cases, "attachments": attachments,
              "gates": {"recallAt5": "pass" if score is not None and score >= .9 else "failed",
                        "grounded90Percent": "not_measured_requires_rubric",
                        "realProvider": "measured" if args.mode == "live" and any(c["status"] == "completed" and any(r.get("model") and r["model"] != "contract-scripted" for r in c.get("usage", [])) for c in cases) else "not_measured",
                        "tokensAndModelRounds": "pass" if all(c.get("usage") and all(r["measured"] for r in c["usage"]) for c in cases) else "not_measured"},
              "limitations": ["Model names are caller declarations; use matching backend startup config, no automatic profile inference.",
                              "Citation resolution is not factual entailment; use the separate grounding rubric.",
                              "Latency includes HTTP polling and is not a load test.",
                              "A live sample of <=12 cases does not establish full-corpus quality.",
                              "Offline scripted runs cannot establish LLM/vision grounding quality."]}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"report": str(args.report), "summary": summary, "gates": report["gates"]}, ensure_ascii=False))
    if args.strict and any(v != "pass" for v in report["gates"].values()):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
