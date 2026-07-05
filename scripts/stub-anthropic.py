#!/usr/bin/env python3
"""Local stub of the Anthropic Messages API for offline testing of --provider sdk.

Behaviour: the 1st call returns 429 (retry-after:1) to exercise the retry; the
following calls return 200 with a JSON array inside ```json ...```. One of them
sends stop_reason=max_tokens to exercise the truncation warning. Each request is
logged (model, max_tokens, thinking) to the file passed as --log.

Usage: python3 scripts/stub-anthropic.py <port> <logfile>
"""
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
LOG = sys.argv[2]
state = {"calls": 0}


def log(msg):
    with open(LOG, "a") as f:
        f.write(msg + "\n")


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("content-length", 0))
        body = json.loads(self.rfile.read(n) or b"{}")
        state["calls"] += 1
        c = state["calls"]
        log(f"CALL {c} model={body.get('model')} max_tokens={body.get('max_tokens')} "
            f"thinking={json.dumps(body.get('thinking'))} x-api-key={self.headers.get('x-api-key')!r}")
        if c == 1:  # prove the retry
            self.send_response(429)
            self.send_header("retry-after", "1")
            self.send_header("content-type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"type":"error","error":{"type":"rate_limit_error"}}')
            return
        # Real anchors from the order-sample fixture so keep-rate is non-zero.
        arr = [
            {"feature": "Orders", "scenario": "Create order", "given": "a customer",
             "when": "POST /orders", "then": "order created", "type": "happy-path",
             "priority": "critical", "verifiedBy": [], "gap": True,
             "coverage": {"level": "partial", "untested": []},
             "evidence": {"entryPoint": "http:POST /orders", "covers": [],
                          "nodes": ["com.example.orders.domain.Order#markPaid"], "assignmentSites": []}},
            {"from": "NEW", "to": "PAID", "trigger": "markPaid",
             "evidence": {"class": "Order", "method": "markPaid",
                          "file": "src/main/java/com/example/orders/domain/Order.java", "line": 36}},
            {"domain": "Orders", "members": ["Order", "Shipment"], "rationale": "core"},
        ]
        stop = "max_tokens" if c == 2 else "end_turn"
        resp = {"id": "msg_stub", "type": "message", "role": "assistant",
                "model": body.get("model"), "stop_reason": stop,
                "content": [{"type": "text", "text": "```json\n" + json.dumps(arr) + "\n```"}],
                "usage": {"input_tokens": 10, "output_tokens": 20}}
        out = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(out)


open(LOG, "w").close()
HTTPServer(("127.0.0.1", PORT), H).serve_forever()
