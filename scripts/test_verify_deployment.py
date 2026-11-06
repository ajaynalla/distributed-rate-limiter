"""Contract/error tests for the deployment verifier; no cloud credentials needed."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from verify_deployment import Client, VerificationError, origin, wait_healthy


class Handler(BaseHTTPRequestHandler):
    status = 200
    payload = {"status": "UP"}
    retry = None

    def do_GET(self):
        self.send_response(type(self).status)
        self.send_header("Content-Type", "application/json")
        if type(self).retry:
            self.send_header("Retry-After", type(self).retry)
        self.end_headers()
        self.wfile.write(json.dumps(type(self).payload).encode())

    do_POST = do_GET

    def log_message(self, *_):
        pass


class VerificationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.client = Client(f"http://127.0.0.1:{cls.server.server_port}")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        Handler.status = 200
        Handler.payload = {"status": "UP"}
        Handler.retry = None

    def test_credentials_and_plaintext_public_urls_are_rejected(self):
        for value in ("https://user:secret@example.com", "http://public.example.com", "https://example.com/path", "https://example.com/?secret=1"):
            with self.assertRaises(VerificationError):
                origin(value)

    def test_actual_http_429_is_a_valid_rejection(self):
        Handler.status = 429
        Handler.retry = "1"
        Handler.payload = {"allowed": False, "remaining": 0, "retryAfterMs": 900, "degraded": False}
        decision, _ = self.client.decision("/check", {"clientId": "x"})
        self.assertFalse(decision["allowed"])

    def test_503_is_not_mistaken_for_a_rate_limit_decision(self):
        Handler.status = 503
        Handler.payload = {"detail": "Lab full"}
        with self.assertRaises(VerificationError):
            self.client.decision("/check", {"clientId": "x"})

    def test_quota_contract_rejects_contradictory_http_status(self):
        Handler.payload = {"allowed": False, "remaining": 0, "retryAfterMs": 900, "degraded": False}
        with self.assertRaises(VerificationError):
            self.client.decision("/check", {"clientId": "x"})

    def test_health_budget_fails_closed_on_non_up_status(self):
        Handler.payload = {"status": "DOWN"}
        with self.assertRaises(VerificationError):
            wait_healthy(self.client, attempts=1, delay=0)


if __name__ == "__main__":
    unittest.main()
