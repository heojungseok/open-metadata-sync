import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


FIXED_FILTER = "from-created-date:2026-08-01,until-created-date:2026-08-08"
PAGE_SIZE = 1_000
PAGE_COUNT = 10


def page_for_cursor(cursor):
    if cursor == "*":
        return 1
    if cursor.startswith("stub-") and cursor[5:].isdigit():
        page = int(cursor[5:])
        if 2 <= page <= PAGE_COUNT + 1:
            return page
    raise ValueError("invalid stub cursor")


def page_payload(page):
    if not 1 <= page <= PAGE_COUNT:
        items = []
    else:
        start = (page - 1) * PAGE_SIZE
        items = [
            {
                "DOI": f"10.5555/live-demo-{index:05d}",
                "title": [f"Live demo work {index:05d}"],
                "publisher": "Open Metadata Sync",
                "type": "journal-article",
                "issued": {"date-parts": [[2026, 8, 1]]},
                "URL": f"https://example.invalid/works/{index:05d}",
                "author": [{"given": "Demo", "family": f"Author {index:05d}"}],
                "indexed": {"date-time": "2026-08-08T00:00:00Z"},
                "created": {"date-time": "2026-08-01T00:00:00Z"},
            }
            for index in range(start, start + PAGE_SIZE)
        ]
    return {
        "status": "ok",
        "message-type": "work-list",
        "message-version": "1.0.0",
        "message": {
            "next-cursor": f"stub-{page + 1}",
            "total-results": PAGE_SIZE * PAGE_COUNT,
            "items-per-page": len(items),
            "items": items,
        },
    }


class StubState:
    def __init__(self):
        self.lock = threading.Lock()
        self.active = 0
        self.max_active = 0
        self.pages = []
        self.started_at = []

    def enter(self, page):
        with self.lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            self.pages.append(page)
            self.started_at.append(time.monotonic())

    def leave(self):
        with self.lock:
            self.active -= 1

    def snapshot(self):
        with self.lock:
            return {
                "pages": list(self.pages),
                "started_at": list(self.started_at),
                "max_active": self.max_active,
            }


STATE = StubState()


class StubHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path == "/metrics" and not parsed.query:
            self._json(200, STATE.snapshot())
            return
        if parsed.path != "/works":
            self._json(404, {"error": "not found"})
            return
        values = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True)
        if set(values) != {"filter", "cursor", "rows"} or any(len(value) != 1 for value in values.values()):
            self._json(400, {"error": "invalid query"})
            return
        if values["filter"][0] != FIXED_FILTER or values["rows"][0] != str(PAGE_SIZE):
            self._json(400, {"error": "invalid contract"})
            return
        try:
            page = page_for_cursor(values["cursor"][0])
        except ValueError:
            self._json(400, {"error": "invalid cursor"})
            return
        STATE.enter(page)
        try:
            fail_page = int(os.environ.get("FAIL_PAGE", "0"))
            if page == fail_page:
                self._json(500, {"error": "injected failure"})
                return
            self._json(200, page_payload(page), {
                "X-Rate-Limit-Limit": "100",
                "X-Rate-Limit-Interval": "1s",
            })
        finally:
            STATE.leave()

    def _json(self, status, value, headers=None):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, header_value in (headers or {}).items():
            self.send_header(name, header_value)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8080"))), StubHandler).serve_forever()
