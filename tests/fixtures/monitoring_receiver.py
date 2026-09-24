"""Disposable local receiver. Never forwards alerts or contacts an external service."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json

EVENTS = []


class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        EVENTS.append({"path": self.path, "payload": payload})
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(EVENTS).encode())

    def log_message(self, *_args):
        pass


HTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
