import unittest

import proxy


class CrossrefProxyContractTest(unittest.TestCase):
    def test_only_fixed_works_query_is_normalized_with_one_mailto(self):
        target = (
            "/works?filter=from-created-date%3A2026-08-01%2Cuntil-created-date%3A2026-08-08"
            "&cursor=next%2Bcursor&rows=1000"
        )

        upstream = proxy.normalized_upstream_target(target, "demo@example.com")

        self.assertEqual(
            upstream,
            "/works?filter=from-created-date%3A2026-08-01%2Cuntil-created-date%3A2026-08-08"
            "&cursor=next%2Bcursor&rows=1000&mailto=demo%40example.com",
        )

    def test_unknown_duplicate_or_wrong_queries_are_rejected(self):
        rejected = (
            "/other?filter=x&cursor=x&rows=1000",
            "/works?filter=x&cursor=x&rows=1000",
            "/works?filter=from-created-date:2026-08-01,until-created-date:2026-08-08&rows=999&cursor=x",
            "/works?filter=from-created-date:2026-08-01,until-created-date:2026-08-08&rows=1000",
            "/works?filter=from-created-date:2026-08-01,until-created-date:2026-08-08&rows=1000&cursor=x&cursor=y",
            "/works?filter=from-created-date:2026-08-01,until-created-date:2026-08-08&rows=1000&cursor=x&mailto=a%40b.com",
            "https://api.crossref.org/works?filter=x",
            "//evil.example/works?filter=x",
        )
        for target in rejected:
            with self.subTest(target=target), self.assertRaises(ValueError):
                proxy.normalized_upstream_target(target, "demo@example.com")

    def test_only_provider_control_headers_are_returned(self):
        headers = proxy.public_response_headers([
            ("Content-Type", "application/json"),
            ("X-Rate-Limit-Limit", "50"),
            ("X-Rate-Limit-Interval", "1s"),
            ("Retry-After", "10"),
            ("Location", "https://evil.example/secret"),
            ("Set-Cookie", "secret=value"),
        ])

        self.assertEqual(headers, {
            "Content-Type": "application/json",
            "X-Rate-Limit-Limit": "50",
            "X-Rate-Limit-Interval": "1s",
            "Retry-After": "10",
        })

    def test_redirects_are_rejected_and_logs_do_not_include_target_or_mailto(self):
        self.assertFalse(proxy.allowed_upstream_status(301))
        self.assertTrue(proxy.allowed_upstream_status(200))
        self.assertTrue(proxy.allowed_upstream_status(429))
        self.assertEqual(proxy.access_log("GET", 200), "method=GET status=200")


if __name__ == "__main__":
    unittest.main()
