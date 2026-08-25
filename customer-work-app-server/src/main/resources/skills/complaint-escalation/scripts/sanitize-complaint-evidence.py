#!/usr/bin/env python3
"""Sanitize minimal complaint evidence. Reads JSON from stdin, writes JSON to stdout."""

import json
import re
import sys


PRIORITIES = {"LOW", "MEDIUM", "HIGH", "URGENT"}
LIMITS = {
    "summary": 300,
    "keyFacts": 500,
    "customerRequest": 240,
    "escalationReason": 240,
}


def redact(value, limit):
    value = "" if value is None else str(value)
    value = re.sub(r"[\r\n\t]+", " ", value).strip()
    value = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[已脱敏]", value)
    value = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[已脱敏]", value)
    value = re.sub(r"(?<!\d)\d{17}[\dXx](?!\d)", "[已脱敏]", value)
    value = re.sub(r"(?<!\d)\d{12,19}(?!\d)", "[已脱敏]", value)
    value = re.sub(r"(地址|收货地址|住址)\s*[:：][^，,。；;\r\n]{0,80}", r"\1:[已脱敏]", value)
    return value if len(value) <= limit else value[: limit - 1] + "…"


def main():
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, TypeError):
        print(json.dumps({"ok": False, "reasonCodes": ["COMPLAINT_EVIDENCE_UNPARSEABLE"]}))
        return

    summary = redact(payload.get("summary"), LIMITS["summary"])
    if not summary:
        print(json.dumps({"ok": False, "reasonCodes": ["COMPLAINT_SUMMARY_MISSING"]}))
        return

    priority = str(payload.get("priority") or "MEDIUM").upper()
    reasons = ["COMPLAINT_EVIDENCE_SANITIZED"]
    if priority not in PRIORITIES:
        priority = "MEDIUM"
        reasons.append("COMPLAINT_PRIORITY_DEFAULTED")

    evidence = {
        "orderId": redact(payload.get("orderId") or "无", 64),
        "summary": summary,
        "keyFacts": redact(payload.get("keyFacts"), LIMITS["keyFacts"]),
        "customerRequest": redact(payload.get("customerRequest"), LIMITS["customerRequest"]),
        "escalationReason": redact(payload.get("escalationReason"), LIMITS["escalationReason"]),
        "priority": priority,
    }
    print(json.dumps({"ok": True, "evidence": evidence, "reasonCodes": reasons}, ensure_ascii=False))


if __name__ == "__main__":
    main()
