import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs


HELPER = Path(__file__).with_name("verify_owner_login.py")


class OwnerLoginContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), JenkinsStub)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()

    def run_helper(self, password):
        self.assertTrue(HELPER.is_file(), "owner login verifier is missing")
        return subprocess.run(
            [sys.executable, str(HELPER), self.origin], input=password + "\n",
            text=True, capture_output=True, timeout=5,
        )

    def test_form_login_session_crumb_and_post_are_verified(self):
        result = self.run_helper("current-password")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("owner session verified", result.stdout)

    def test_invalid_password_is_rejected(self):
        self.assertNotEqual(self.run_helper("wrong-password").returncode, 0)

    def test_cross_origin_redirect_is_rejected(self):
        result = self.run_helper("cross-origin")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cross-origin redirect", result.stderr)

    def test_logout_requires_crumb(self):
        result = self.run_helper("no-csrf")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("logout without crumb was accepted", result.stderr)

    def test_logout_must_end_the_session(self):
        result = self.run_helper("sticky-session")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("session remained authenticated", result.stderr)


class JenkinsStub(BaseHTTPRequestHandler):
    def do_GET(self):
        authenticated = any(
            session in self.headers.get("Cookie", "")
            for session in ("JSESSIONID=owner", "JSESSIONID=no-csrf", "JSESSIONID=sticky")
        )
        if self.path == "/login":
            self.reply(200, b"login")
        elif self.path == "/manage":
            self.reply(200 if authenticated else 403, b"manage")
        elif self.path == "/whoAmI/api/json":
            body = (b'{"authenticated":true,"name":"heojungseok"}' if authenticated
                    else b'{"authenticated":false,"name":"anonymous"}')
            self.reply(200, body, "application/json")
        elif self.path == "/crumbIssuer/api/json":
            self.reply(200 if authenticated else 403,
                       b'{"crumbRequestField":"Jenkins-Crumb","crumb":"crumb-1"}',
                       "application/json")
        elif self.path == "/":
            self.reply(200, b"home")
        else:
            self.reply(404, b"missing")

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(size).decode())
        if self.path == "/j_spring_security_check":
            password = form.get("j_password", [""])[0]
            if password == "cross-origin":
                self.redirect("https://example.invalid/")
            elif password in ("current-password", "no-csrf", "sticky-session"):
                session = {
                    "current-password": "owner",
                    "no-csrf": "no-csrf",
                    "sticky-session": "sticky",
                }[password]
                self.send_response(302)
                self.send_header("Set-Cookie", f"JSESSIONID={session}; Path=/; HttpOnly")
                self.send_header("Location", "/manage")
                self.end_headers()
            else:
                self.redirect("/loginError")
        elif self.path == "/logout":
            cookie = self.headers.get("Cookie", "")
            if "JSESSIONID=no-csrf" in cookie and not self.headers.get("Jenkins-Crumb"):
                self.redirect("/")
            elif self.headers.get("Jenkins-Crumb") != "crumb-1":
                self.reply(403, b"forbidden")
            elif "JSESSIONID=sticky" in cookie:
                self.redirect("/")
            else:
                self.send_response(302)
                self.send_header("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0")
                self.send_header("Location", "/")
                self.send_header("Content-Length", "0")
                self.end_headers()
        else:
            self.reply(403, b"forbidden")

    def reply(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def redirect(self, location):
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, _format, *_args):
        pass


if __name__ == "__main__":
    unittest.main()
