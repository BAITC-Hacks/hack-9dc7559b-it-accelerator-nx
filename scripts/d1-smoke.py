#!/usr/bin/env python3
"""HTTP smoke for the contract profile; creates and revokes two visitor sessions.

Requires a running backend with contract fixtures, visitor sessions and workers enabled.
Uses only Python's standard library. Tokens stay in memory and Authorization headers.
"""

import argparse
import json
from decimal import Decimal
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4


class SmokeFailure(Exception):
    pass


def check(condition, message):
    if not condition:
        raise SmokeFailure(message)


class NoRedirects(HTTPRedirectHandler):
    # Never forward a bearer token to a redirect destination.
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.opener = build_opener(NoRedirects())

    def request(self, method, path, token=None, body=None, key=None,
                expected=(200,), cursor=None, sse=False):
        headers = {"Accept": "text/event-stream" if sse else "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        if cursor:
            headers["Last-Event-ID"] = cursor
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=15)
        except HTTPError as error:
            response = error
        except (URLError, TimeoutError, OSError):
            raise SmokeFailure(f"{method} {path}: connection failed or timed out") from None
        with response:
            status = response.status
            check(status in expected,
                  f"{method} {path}: HTTP {status}, expected {expected}")
            raw = response.read(4 * 1024 * 1024 + 1)
            check(len(raw) <= 4 * 1024 * 1024, "Response exceeded the smoke size limit")
            if sse:
                check("text/event-stream" in response.headers.get("Content-Type", ""),
                      "SSE endpoint returned the wrong content type")
                return parse_sse(raw.decode("utf-8"))
            return json.loads(raw) if raw.strip() else None


def parse_sse(wire):
    events = []
    for block in wire.replace("\r\n", "\n").split("\n\n"):
        fields = {}
        data = []
        for line in block.splitlines():
            if not line or line.startswith(":"):
                continue
            name, separator, value = line.partition(":")
            if separator and value.startswith(" "):
                value = value[1:]
            if name == "data":
                data.append(value)
            else:
                fields[name] = value
        if not data:
            continue
        event = json.loads("\n".join(data))
        check(fields.get("id") == event.get("eventId"), "SSE id disagrees with its envelope")
        check(fields.get("event") == event.get("type"), "SSE type disagrees with its envelope")
        events.append(event)
    return events


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def smoke(args):
    client = Client(args.base_url)
    tokens = []
    try:
        session = client.request("POST", "/auth/visitor-session")
        token = session["accessToken"]
        tokens.append(token)
        conversation = client.request("POST", "/api/conversations", token, expected=(201,))
        conversation_path = "/api/conversations/" + conversation["id"]
        turn_key = "smoke-turn-" + uuid4().hex
        turn_body = {"text": "DEMO-001"}
        accepted = client.request("POST", conversation_path + "/messages", token,
                                  turn_body, turn_key, expected=(202,))
        run_path = "/api/runs/" + accepted["runId"]
        until = time.monotonic() + args.deadline_seconds
        while True:
            run = client.request("GET", run_path, token)
            if run["status"] in ("completed", "failed", "cancelled"):
                break
            check(time.monotonic() < until, "Agent run did not finish before the polling deadline")
            time.sleep(0.25)
        check(run["status"] == "completed", "Exact-SKU agent run did not complete successfully")
        replayed_turn = client.request("POST", conversation_path + "/messages", token,
                                      turn_body, turn_key, expected=(202,))
        check(replayed_turn == accepted, "A repeated message created another run")
        history = client.request("GET", conversation_path + "/messages", token)["items"]
        check(len(history) == 2 and history[0]["role"] == "user"
              and history[0]["text"] == "DEMO-001" and history[1]["role"] == "assistant",
              "Completed turn did not persist exactly one user and assistant message")
        check(all(message["runId"] == accepted["runId"] for message in history),
              "Saved history references the wrong run")

        events = client.request("GET", run_path + "/events", token, sse=True)
        check(bool(events), "SSE replay was empty")
        check(all(event["runId"] == accepted["runId"] for event in events), "SSE leaked another run")
        sequences = [int(event["seq"]) for event in events]
        check(sequences == list(range(1, int(run["sequence"]) + 1)), "SSE replay has missing or repeated events")
        by_type = {}
        for event in events:
            by_type.setdefault(event["type"], []).append(event)
        for required in ("run.started", "message.delta", "products.result", "run.completed"):
            check(required in by_type, f"SSE replay is missing {required}")
        terminal = by_type["run.completed"][-1]
        check(terminal["payload"]["snapshot"]["status"] == "completed", "Terminal snapshot is inconsistent")
        resumed = client.request("GET", run_path + "/events", token,
                                 cursor=events[0]["eventId"], sse=True)
        check(resumed == events[1:], "Last-Event-ID replay did not return exactly the unseen suffix")

        state = client.request("GET", conversation_path + "/state", token)
        result_id = state["lastResultSetId"]
        check(bool(result_id), "Completed search did not persist a result set")
        results = client.request("GET", conversation_path + "/results/" + result_id, token)
        check(any(product["article"] == "DEMO-001" for product in results["products"]),
              "Exact SKU is missing from the saved result set")
        offers = [offer for offer in results["offers"] if offer["article"] == "DEMO-001"]
        check(bool(offers), "Exact SKU has no authoritative offer")
        offer = offers[0]
        proposal = client.request("POST", "/api/cart/proposals", token, {
            "conversationId": conversation["id"], "expectedStateVersion": state["version"],
            "resultSetId": result_id, "lines": [{"article": "DEMO-001",
                "unit": offer["available"]["unit"], "warehouse": offer["warehouse"],
                "addQuantity": "2"}],
        }, "smoke-proposal-" + uuid4().hex)
        check(proposal["status"] == "pending", "Prepared proposal is not pending")
        check(Decimal(proposal["lines"][0]["addQuantity"]["value"]) == Decimal(2),
              "Proposal changed the requested quantity")
        cart = client.request("GET", "/api/cart", token)
        check(cart["lines"] == [], "Preparing a proposal mutated the cart without consent")

        confirm_path = "/api/cart/proposals/" + proposal["id"] + "/confirm"
        consent = {"revision": proposal["revision"], "digest": proposal["digest"], "origin": "button"}
        stale = dict(consent, revision=str(int(proposal["revision"]) + 1))
        conflict = client.request("POST", confirm_path, token, stale,
                                  "smoke-stale-" + uuid4().hex, expected=(409,))
        check(conflict.get("code") == "stale_proposal", "Stale confirmation returned the wrong conflict")
        check(client.request("GET", "/api/cart", token)["lines"] == [],
              "A stale confirmation mutated the cart")
        confirm_key = "smoke-confirm-" + uuid4().hex
        outcome = client.request("POST", confirm_path, token, consent, confirm_key)
        until = time.monotonic() + args.deadline_seconds
        while outcome["status"] == "outcome_unknown":
            check(time.monotonic() < until, "Cart outcome stayed unknown past the polling deadline")
            time.sleep(0.25)
            outcome = client.request("GET", "/api/cart/operations/" + outcome["id"], token)
        check(outcome["status"] == "succeeded", "Confirmed cart operation did not succeed")
        cart = client.request("GET", "/api/cart", token)
        check(len(cart["lines"]) == 1 and cart["lines"][0]["article"] == "DEMO-001",
              "Cart contains unexpected products")
        check(Decimal(cart["lines"][0]["quantity"]["value"]) == Decimal(2), "Cart quantity is not exactly two")
        duplicate = client.request("POST", confirm_path, token, consent, confirm_key)
        check(duplicate == outcome, "Duplicate confirmation changed the operation outcome")
        check(client.request("GET", "/api/cart", token) == cart, "Duplicate confirmation mutated the cart")

        stranger = client.request("POST", "/auth/visitor-session")["accessToken"]
        tokens.append(stranger)
        client.request("GET", run_path, stranger, expected=(403, 404))
        client.request("POST", "/auth/logout", token)
        tokens.remove(token)
        client.request("GET", run_path, token, expected=(401,))
        client.request("POST", "/auth/logout", stranger)
        tokens.remove(stranger)

        if args.fixtures_dir:
            fixtures = {"accepted": accepted, "delta": by_type["message.delta"][0],
                        "products": by_type["products.result"][0], "proposal": proposal,
                        "terminal": terminal, "conflict": conflict}
            for name, value in fixtures.items():
                write_json(args.fixtures_dir / (name + ".json"), value)
        if args.export_schema:
            schema = client.request("GET", "/v3/api-docs")
            check("openapi" in schema and "/api/cart/proposals" in schema.get("paths", {}),
                  "OpenAPI export is missing the cart contract")
            write_json(Path(__file__).resolve().parents[1] / "docs/api/openapi.json", schema)
        print("PASS: exact SKU, durable history, SSE replay, consent, cart idempotency, isolation and logout")
        if args.fixtures_dir:
            print("Saved six actual HTTP/SSE fixtures; no session tokens were exported")
        if args.export_schema:
            print("Exported actual OpenAPI schema to docs/api/openapi.json")
    finally:
        for remaining in tokens:
            try:
                client.request("POST", "/auth/logout", remaining, expected=(200, 204, 401))
            except Exception:
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Running contract-profile backend URL")
    parser.add_argument("--deadline-seconds", type=int, default=90, help="Polling deadline per operation (default: 90)")
    parser.add_argument("--fixtures-dir", type=Path, help="Save six actual API/SSE JSON fixtures")
    parser.add_argument("--export-schema", action="store_true", help="Export /v3/api-docs to docs/api/openapi.json")
    args = parser.parse_args()
    parsed = urlsplit(args.base_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error("--base-url must be an HTTP(S) URL without credentials, query or fragment")
    if args.deadline_seconds < 1:
        parser.error("--deadline-seconds must be positive")
    try:
        smoke(args)
    except SmokeFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        # Do not print raw bodies, request headers or exception payloads containing credentials.
        print("FAIL: unexpected response or local I/O error (" + type(error).__name__ + ")", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
