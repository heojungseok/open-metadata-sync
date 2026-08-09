import http.client
import os

import proxy


def stub_connection():
    return http.client.HTTPConnection("crossref-stub", 8080, timeout=15)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    proxy.CrossrefProxyServer(
        ("0.0.0.0", port), "e2e@example.invalid", stub_connection
    ).serve_forever()
