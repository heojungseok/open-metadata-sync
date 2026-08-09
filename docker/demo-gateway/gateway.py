#!/usr/bin/env python3
import http.client
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import urlopen


JENKINS_ORIGIN = os.environ.get("JENKINS_ORIGIN", "http://jenkins:8080").rstrip("/")
ORIGIN = urlsplit(JENKINS_ORIGIN)
BUILD_JOBS = (
    "/job/open-metadata-sync-demo-10k/",
    "/job/open-metadata-sync-demo-replay/",
)
BUILD_SUFFIXES = ("build", "buildWithParameters")
BLOCKED_PREFIXES = (
    "/login", "/manage", "/script", "/configure", "/credentials",
    "/computer", "/pluginManager", "/securityRealm", "/user",
)
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}


def request_path(path):
    return urlsplit(path).path


def is_public_build_path(path):
    clean = request_path(path)
    return any(clean == prefix + suffix for prefix in BUILD_JOBS for suffix in BUILD_SUFFIXES)


def normalized_build_parameters(path, body, now_ms=None):
    clean = request_path(path)
    submitted = parse_qs(body.decode("utf-8"), keep_blank_values=True)
    request_id = f"public-{now_ms if now_ms is not None else time.time_ns() // 1_000_000}"
    if clean.startswith(BUILD_JOBS[0]):
        scenario = submitted.get("DEMO_SCENARIO", ["INITIAL"])[0]
        chunk_size = submitted.get("CHUNK_SIZE", ["1000"])[0]
        if scenario not in {"INITIAL", "NO_OP"}:
            raise ValueError("invalid demo scenario")
        if chunk_size not in {"100", "500", "1000", "2000"}:
            raise ValueError("invalid chunk size")
        params = {
            "REQUEST_ID": request_id,
            "DEMO_SCENARIO": scenario,
            "SEED": "20260809",
            "CHUNK_SIZE": chunk_size,
        }
    elif clean.startswith(BUILD_JOBS[1]):
        params = {
            "REQUEST_ID": request_id,
            "MODE": "REPLAY_ERRORS",
            "CREATED_FROM": "",
            "CREATED_UNTIL": "",
            "MAX_ITEMS": "",
            "SOURCE_NAME": "crossref",
            "BOOTSTRAP_INDEXED_FROM": "",
            "INDEXED_FROM_UTC": "",
            "INDEXED_UNTIL_UTC": "",
            "SOURCE_EXECUTION_ID": "00000000-0000-0000-0000-00000000d001",
            "CHUNK_SIZE": "1000",
            "HIBERNATE_BATCH_SIZE": "1000",
        }
    else:
        raise ValueError("build path is not public")
    return urlencode(params).encode("utf-8")


class AdmissionState:
    def __init__(self, reservation_seconds=10):
        self.reservation_seconds = reservation_seconds
        self.reserved_until = 0
        self.lock = threading.Lock()

    def try_admit(self, queue_count, busy_executors, now=None):
        now = time.monotonic() if now is None else now
        with self.lock:
            if queue_count or busy_executors or now < self.reserved_until:
                return False
            self.reserved_until = now + self.reservation_seconds
            return True

    def release(self):
        with self.lock:
            self.reserved_until = 0


ADMISSION = AdmissionState(int(os.environ.get("ADMISSION_RESERVATION_SECONDS", "10")))


def read_json(path):
    with urlopen(JENKINS_ORIGIN + path, timeout=2) as response:
        return json.load(response)


def backend_load():
    queue_count = len(read_json("/queue/api/json?tree=items[id]").get("items", []))
    busy = 0
    for job in ("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay"):
        data = read_json(f"/job/{job}/api/json?tree=lastBuild[building]")
        if (data.get("lastBuild") or {}).get("building"):
            busy += 1
    return queue_count, busy


def crumb_headers_from_response(data, response_headers):
    headers = {data["crumbRequestField"]: data["crumb"]}
    cookies = [value.split(";", 1)[0] for name, value in response_headers
               if name.lower() == "set-cookie"]
    if cookies:
        headers["Cookie"] = "; ".join(cookies)
    return headers


def backend_crumb_headers():
    connection = http.client.HTTPConnection(ORIGIN.hostname, ORIGIN.port or 80, timeout=2)
    try:
        connection.request("GET", "/crumbIssuer/api/json")
        response = connection.getresponse()
        payload = response.read()
        if response.status != 200:
            raise RuntimeError("crumb issuer unavailable")
        return crumb_headers_from_response(json.loads(payload), response.getheaders())
    finally:
        connection.close()


class GatewayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "OpenMetadataDemoGateway/1"

    def do_GET(self):
        if request_path(self.path) == "/healthz":
            self._reply(200, b"ok\n", "text/plain")
            return
        if request_path(self.path).startswith(BLOCKED_PREFIXES):
            self._reply(404, b"not found\n", "text/plain")
            return
        self._proxy()

    do_HEAD = do_GET

    def do_POST(self):
        if not is_public_build_path(self.path):
            self._reply(403, b"public demo only permits the two build actions\n", "text/plain")
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._reply(400, b"invalid content length\n", "text/plain")
            return
        if size > 16_384:
            self._reply(413, b"request too large\n", "text/plain")
            return
        raw = self.rfile.read(size)
        try:
            body = normalized_build_parameters(self.path, raw)
            queued, busy = backend_load()
        except ValueError as error:
            self._reply(400, (str(error) + "\n").encode(), "text/plain")
            return
        except Exception:
            self._reply(503, b"demo admission unavailable\n", "text/plain")
            return
        if not ADMISSION.try_admit(queued, busy):
            self._reply(429, b"another demo build is queued or running\n", "text/plain")
            return
        try:
            backend_headers = backend_crumb_headers()
        except Exception:
            ADMISSION.release()
            self._reply(503, b"demo csrf admission unavailable\n", "text/plain")
            return
        status = self._proxy(body, backend_headers)
        if status is None or status >= 400:
            ADMISSION.release()

    def _proxy(self, body=None, backend_headers=None):
        target = urlsplit(self.path)
        connection = http.client.HTTPConnection(ORIGIN.hostname, ORIGIN.port or 80, timeout=60)
        headers = {
            name: value for name, value in self.headers.items()
            if name.lower() not in HOP_BY_HOP and name.lower() not in {"host", "content-length"}
        }
        headers["Host"] = self.headers.get("Host", "demo.heojungseok.com")
        headers["X-Forwarded-Proto"] = "https"
        headers["X-Forwarded-Host"] = headers["Host"]
        if body is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            headers["Content-Length"] = str(len(body))
        if backend_headers:
            headers.update(backend_headers)
        method = "POST" if body is not None else self.command
        try:
            connection.request(method, target.path + (("?" + target.query) if target.query else ""), body, headers)
            response = connection.getresponse()
            payload = b"" if self.command == "HEAD" else response.read()
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() not in HOP_BY_HOP and name.lower() != "content-length":
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if payload:
                self.wfile.write(payload)
            return response.status
        except Exception:
            self._reply(502, b"demo backend unavailable\n", "text/plain")
            return None
        finally:
            connection.close()

    def _reply(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        ray = self.headers.get("CF-Ray", "local")
        print(f"{self.client_address[0]} ray={ray} {fmt % args}", flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), GatewayHandler)
    server.serve_forever()
