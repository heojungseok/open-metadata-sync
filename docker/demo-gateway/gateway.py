#!/usr/bin/env python3
import http.client
import json
import os
import secrets
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlencode, urlsplit
from urllib.request import urlopen


JENKINS_ORIGIN = os.environ.get("JENKINS_ORIGIN", "http://jenkins:8080").rstrip("/")
ORIGIN = urlsplit(JENKINS_ORIGIN)
BUILD_JOBS = (
    "/job/open-metadata-sync-demo-10k/",
    "/job/open-metadata-sync-demo-replay/",
)
BUILD_SUFFIXES = ("build", "buildWithParameters")
LIVE_PARAMETER_NAMES = ("REQUEST_ID", "CHUNK_SIZE")
REPLAY_PARAMETER_NAMES = (
    "REQUEST_ID", "MODE", "CREATED_FROM", "CREATED_UNTIL", "MAX_ITEMS",
    "SOURCE_NAME", "BOOTSTRAP_INDEXED_FROM", "INDEXED_FROM_UTC",
    "INDEXED_UNTIL_UTC", "SOURCE_EXECUTION_ID", "CHUNK_SIZE",
    "HIBERNATE_BATCH_SIZE",
)
CHUNK_SIZES = {"100", "500", "1000", "2000"}
PROVIDER_COOLDOWN_SECONDS = int(os.environ.get("PROVIDER_COOLDOWN_SECONDS", "300"))
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


@dataclass(frozen=True)
class NormalizedBuild:
    body: bytes
    request_id: str


def normalized_build_request(path, body, now_ms=None, random_token=None):
    clean = request_path(path)
    suffix = clean.rsplit("/", 1)[-1]
    _validate_query(path, suffix)
    expected_names = LIVE_PARAMETER_NAMES if clean.startswith(BUILD_JOBS[0]) else REPLAY_PARAMETER_NAMES
    if not any(clean.startswith(job) for job in BUILD_JOBS):
        raise ValueError("build path is not public")
    submitted = (_structured_parameters(body, expected_names) if suffix == "build"
                 else _flat_parameters(body, expected_names, clean.startswith(BUILD_JOBS[0])))
    timestamp = now_ms if now_ms is not None else time.time_ns() // 1_000_000
    token = random_token if random_token is not None else secrets.token_hex(8)
    request_id = f"public-{timestamp}-{token}"
    if clean.startswith(BUILD_JOBS[0]):
        chunk_size = submitted["CHUNK_SIZE"]
        if chunk_size not in CHUNK_SIZES:
            raise ValueError("invalid chunk size")
        params = {
            "REQUEST_ID": request_id,
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
    if suffix == "build":
        envelope = {"parameter": [{"name": name, "value": params[name]} for name in params]}
        normalized = urlencode((
            ("json", json.dumps(envelope, separators=(",", ":"))),
            ("Submit", "Build"),
        )).encode("utf-8")
    else:
        normalized = urlencode(params).encode("utf-8")
    return NormalizedBuild(normalized, request_id)


def normalized_build_parameters(path, body, now_ms=None):
    return normalized_build_request(path, body, now_ms=now_ms).body


def _form_pairs(body):
    try:
        return parse_qsl(
            body.decode("utf-8", errors="strict"), keep_blank_values=True,
            strict_parsing=True, encoding="utf-8", errors="strict",
        )
    except (UnicodeDecodeError, ValueError) as error:
        raise ValueError("invalid form body") from error


def _validate_query(path, suffix):
    try:
        query = parse_qsl(urlsplit(path).query, keep_blank_values=True, strict_parsing=True)
    except ValueError as error:
        raise ValueError("invalid build query") from error
    if suffix == "build":
        if query not in ([], [("delay", "0sec")]):
            raise ValueError("invalid build query")
    elif query:
        raise ValueError("buildWithParameters does not accept query parameters")


def _structured_parameters(body, expected_names):
    pairs = _form_pairs(body)
    allowed = {"json", "Submit", "name", "value"}
    if any(name not in allowed for name, _ in pairs):
        raise ValueError("structured build contains mixed or unknown fields")
    json_values = [value for name, value in pairs if name == "json"]
    submit_values = [value for name, value in pairs if name == "Submit"]
    if len(json_values) != 1 or len(submit_values) > 1 or (submit_values and submit_values != ["Build"]):
        raise ValueError("structured build requires one json envelope")
    try:
        envelope = json.loads(json_values[0])
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError("invalid json envelope") from error
    if not isinstance(envelope, dict) or "parameter" not in envelope:
        raise ValueError("invalid json envelope")
    if set(envelope) - {"parameter", "statusCode", "redirectTo"}:
        raise ValueError("unknown json envelope field")
    items = envelope["parameter"]
    if not isinstance(items, list):
        raise ValueError("parameter must be an array")
    submitted = {}
    for item in items:
        if not isinstance(item, dict) or set(item) != {"name", "value"}:
            raise ValueError("invalid structured parameter")
        name, value = item["name"], item["value"]
        if not isinstance(name, str) or not isinstance(value, str) or name in submitted:
            raise ValueError("invalid or duplicate structured parameter")
        submitted[name] = value
    if set(submitted) != set(expected_names):
        raise ValueError("structured parameter set does not match job")
    return submitted


def _flat_parameters(body, expected_names, live):
    pairs = _form_pairs(body)
    submitted = {}
    for name, value in pairs:
        if name in submitted or name not in expected_names:
            raise ValueError("invalid or duplicate flat parameter")
        submitted[name] = value
    if live:
        if set(submitted) != {"CHUNK_SIZE"}:
            raise ValueError("live build requires only CHUNK_SIZE")
    return submitted


def cooldown_remaining_seconds(build, now_ms=None):
    if not build or build.get("building") or not build.get("result"):
        return 0
    terminal_ms = int(build.get("timestamp", 0)) + int(build.get("duration", 0))
    current_ms = now_ms if now_ms is not None else time.time_ns() // 1_000_000
    remaining_ms = terminal_ms + PROVIDER_COOLDOWN_SECONDS * 1_000 - current_ms
    return max(0, (remaining_ms + 999) // 1_000)


def correlation_log(ray, request_id, message):
    return f"ray={ray} request_id={request_id or '-'} {message}"


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
    live_build = None
    for job in ("open-metadata-sync-demo-10k", "open-metadata-sync-demo-replay"):
        data = read_json(f"/job/{job}/api/json?tree=lastBuild[building,result,timestamp,duration]")
        if (data.get("lastBuild") or {}).get("building"):
            busy += 1
        if job == "open-metadata-sync-demo-10k":
            live_build = data.get("lastBuild")
    return queue_count, busy, cooldown_remaining_seconds(live_build)


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
            normalized = normalized_build_request(self.path, raw)
            self.demo_request_id = normalized.request_id
            queued, busy, cooldown = backend_load()
        except ValueError as error:
            self._reply(400, (str(error) + "\n").encode(), "text/plain")
            return
        except Exception:
            self._reply(503, b"demo admission unavailable\n", "text/plain")
            return
        if cooldown or not ADMISSION.try_admit(queued, busy):
            self._reply(429, b"another demo build is queued, running, or cooling down\n", "text/plain",
                        {"Retry-After": str(max(1, cooldown)), "X-Demo-Request-Id": normalized.request_id})
            return
        try:
            backend_headers = backend_crumb_headers()
        except Exception:
            ADMISSION.release()
            self._reply(503, b"demo csrf admission unavailable\n", "text/plain")
            return
        status = self._proxy(normalized.body, backend_headers,
                             {"X-Demo-Request-Id": normalized.request_id})
        if status is None or status >= 400:
            ADMISSION.release()

    def _proxy(self, body=None, backend_headers=None, response_headers=None):
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
            for name, value in (response_headers or {}).items():
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

    def _reply(self, status, body, content_type, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_message(self, fmt, *args):
        ray = self.headers.get("CF-Ray", "local")
        request_id = getattr(self, "demo_request_id", None)
        print(f"{self.client_address[0]} {correlation_log(ray, request_id, fmt % args)}", flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), GatewayHandler)
    server.serve_forever()
