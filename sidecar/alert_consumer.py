#!/usr/bin/env python3
"""Alert consumer for the agentic-banking platform event stream.

Polyglot companion service: the Java platform emits platform events
(loan.decided, incident.resolved, grievance.handled, document.processed,
approval.decided); this Python service consumes them and renders alerts.

Today it polls GET /api/ops/events (pull consumer). In a production deployment,
the same handler logic could attach to Kafka/Event Hubs
topics as a consumer group - the event contract does not change.

Stdlib only: no pip install required.

Usage:
    python3 sidecar/alert_consumer.py [--base-url http://localhost:8080]
                                      [--api-key KEY] [--interval 5] [--once]
"""
import argparse
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone

# Events that should page/notify vs. quietly audit
ALERT_TYPES = {
    "incident.resolved": ["ESCALATED", "PENDING_HUMAN_APPROVAL"],
    "grievance.handled": ["PENDING_HUMAN_REVIEW", "CONFIDENTIAL_ESCALATION"],
    "approval.decided": ["REJECTED"],
}


def fetch_events(base_url, api_key, limit=50):
    req = urllib.request.Request(f"{base_url}/api/ops/events?limit={limit}")
    if api_key:
        req.add_header("X-API-Key", api_key)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode())


def should_alert(event):
    triggers = ALERT_TYPES.get(event.get("type"))
    return bool(triggers) and any(t in event.get("summary", "") for t in triggers)


def render(event, alert):
    icon = "[ALERT]" if alert else "[audit]"
    return (f"{icon} {event['at']}  {event['type']:<20} {event['flow']:<10} "
            f"{event['subjectId']:<14} {event['summary']}")


def run(base_url, api_key, interval, once):
    seen = set()
    alerts = 0
    while True:
        try:
            events = fetch_events(base_url, api_key)
        except Exception as exc:  # noqa: BLE001 - consumer must survive platform restarts
            print(f"[warn] cannot reach platform: {exc}", file=sys.stderr)
            events = []
        for event in reversed(events):  # oldest first
            key = (event["type"], event["subjectId"], event["at"])
            if key in seen:
                continue
            seen.add(key)
            alert = should_alert(event)
            alerts += alert
            print(render(event, alert), flush=True)
        if once:
            print(f"consumer up to date ({len(seen)} events, {alerts} alerts, "
                  f"{datetime.now(timezone.utc).isoformat()})")
            return
        time.sleep(interval)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Agentic-banking alert consumer")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--interval", type=float, default=5.0)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    run(args.base_url, args.api_key, args.interval, args.once)
