import http.client
import os
import ssl
import stat
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlsplit


UPSTREAM_HOST = "api.crossref.org"
UPSTREAM_PORT = 443
FIXED_FILTER = "from-created-date:2026-08-01,until-created-date:2026-08-08"
ALLOWED_RESPONSE_HEADERS = {
    "content-type": "Content-Type",
    "x-rate-limit-limit": "X-Rate-Limit-Limit",
    "x-rate-limit-interval": "X-Rate-Limit-Interval",
    "retry-after": "Retry-After",
}


def normalized_upstream_target(target, mailto):
    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or parsed.fragment or parsed.path != "/works":
        raise ValueError("Only the fixed Crossref works endpoint is allowed")
    try:
        pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
    except (UnicodeDecodeError, ValueError) as exception:
        raise ValueError("Malformed Crossref query") from exception
    values = {}
    for name, value in pairs:
        if name not in {"filter", "cursor", "rows"} or name in values:
            raise ValueError("Unknown or duplicate Crossref query")
        values[name] = value
    if set(values) != {"filter", "cursor", "rows"}:
        raise ValueError("Missing Crossref query")
    if values["filter"] != FIXED_FILTER or values["rows"] != "1000" or not values["cursor"]:
        raise ValueError("Crossref query is outside the public demo contract")
    if not mailto or "@" not in mailto or any(char in mailto for char in "\r\n"):
        raise ValueError("Invalid Crossref mailto")
    return "/works?" + urlencode([
        ("filter", FIXED_FILTER),
        ("cursor", values["cursor"]),
        ("rows", "1000"),
        ("mailto", mailto),
    ])


def public_response_headers(headers):
    result = {}
    for name, value in headers:
        public_name = ALLOWED_RESPONSE_HEADERS.get(name.lower())
        if public_name is not None and public_name not in result:
            result[public_name] = value
    return result


def allowed_upstream_status(status):
    return not 300 <= status < 400


def access_log(method, status):
    return f"method={method} status={status}"


def fixed_upstream_connection():
    return http.client.HTTPSConnection(
        UPSTREAM_HOST,
        UPSTREAM_PORT,
        timeout=15,
        context=ssl.create_default_context(),
    )


def read_mailto_secret(path):
    mode = stat.S_IMODE(os.stat(path).st_mode)
    if mode & 0o077:
        raise ValueError("Crossref mailto secret must not be group/world accessible")
    value = open(path, encoding="utf-8").read().strip()
    if not value:
        raise ValueError("Crossref mailto secret is empty")
    return value


class CrossrefProxyHandler(BaseHTTPRequestHandler):
    server_version = "CrossrefDemoProxy/1"

    def do_GET(self):
        if urlsplit(self.path).path == "/healthz" and not urlsplit(self.path).query:
            self._reply(200, b"ok\n", {"Content-Type": "text/plain"})
            return
        try:
            target = normalized_upstream_target(self.path, self.server.mailto)
        except ValueError:
            self._reply(400, b"Invalid Crossref request\n")
            return
        connection = self.server.connection_factory()
        try:
            connection.request("GET", target, headers={
                "Accept": "application/json",
                "User-Agent": "open-metadata-sync-public-demo/1.0",
            })
            response = connection.getresponse()
            if not allowed_upstream_status(response.status):
                response.read()
                self._reply(502, b"Crossref redirect rejected\n")
                return
            self.send_response(response.status)
            for name, value in public_response_headers(response.getheaders()).items():
                self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            while chunk := response.read(64 * 1024):
                self.wfile.write(chunk)
        except (OSError, http.client.HTTPException, ssl.SSLError):
            self._reply(502, b"Crossref upstream unavailable\n")
        finally:
            connection.close()

    def _method_not_allowed(self):
        self._reply(405, b"Method not allowed\n", {"Allow": "GET"})

    do_POST = do_CONNECT = do_PUT = do_DELETE = do_OPTIONS = _method_not_allowed

    def _reply(self, status, body, headers=None):
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def send_response(self, code, message=None):
        self.response_status = code
        super().send_response(code, message)

    def log_message(self, _format, *_args):
        print(access_log(self.command, getattr(self, "response_status", 0)), file=sys.stderr)


class CrossrefProxyServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, mailto, connection_factory=fixed_upstream_connection):
        super().__init__(address, CrossrefProxyHandler)
        self.mailto = mailto
        self.connection_factory = connection_factory


def main():
    secret_path = os.environ.get("CROSSREF_MAILTO_FILE", "/run/secrets/crossref_mailto")
    mailto = read_mailto_secret(secret_path)
    port = int(os.environ.get("PORT", "8080"))
    CrossrefProxyServer(("0.0.0.0", port), mailto).serve_forever()


if __name__ == "__main__":
    main()
