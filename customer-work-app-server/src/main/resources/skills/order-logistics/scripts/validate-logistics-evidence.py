#!/usr/bin/env python3
"""Validate a structured logistics result. Reads JSON from stdin, writes JSON to stdout."""

import json
import sys

ESCALATION_REASONS = {
    "LOGISTICS_SIGNATURE_DISPUTE",
    "LOGISTICS_LOST",
    "LOGISTICS_DAMAGED",
}


def result(ok, decision, reason_codes):
    return {"ok": ok, "decision": decision, "reasonCodes": reason_codes}


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, TypeError):
        print(json.dumps(result(False, "HUMAN_REVIEW", ["LOGISTICS_RESULT_UNPARSEABLE"])))
        return

    reasons = []
    if payload.get("queryStatus") != "FOUND":
        reasons.append("LOGISTICS_QUERY_NOT_FOUND_OR_UNAVAILABLE")
        print(json.dumps(result(False, "HUMAN_REVIEW", reasons)))
        return
    if not payload.get("events"):
        reasons.append("LOGISTICS_TRACE_MISSING")
    if payload.get("logisticsStatus") == "UNKNOWN":
        reasons.append("LOGISTICS_STATUS_UNKNOWN")
    if payload.get("freshnessStatus") == "STALE":
        reasons.append("LOGISTICS_FRESHNESS_STALE")
    reasons.extend(code for code in payload.get("reasonCodes", [])
                   if code in ESCALATION_REASONS)
    if payload.get("requestType") in {"ETA", "DELAY", "ON_TIME"}:
        if payload.get("freshnessStatus") != "FRESH":
            reasons.append("LOGISTICS_FRESHNESS_NOT_VERIFIED")
        if not payload.get("promisedDeliveryAt"):
            reasons.append("DELIVERY_PROMISE_UNAVAILABLE")

    if reasons:
        print(json.dumps(result(False, "HUMAN_REVIEW", reasons)))
    else:
        print(json.dumps(result(True, "ANSWER_WITH_FACTS", [])))


if __name__ == "__main__":
    main()
