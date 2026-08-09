import unittest
from urllib.parse import parse_qs

import gateway


class GatewayContractTest(unittest.TestCase):
    def test_only_two_public_build_paths_are_accepted(self):
        self.assertTrue(gateway.is_public_build_path(
            "/job/open-metadata-sync-demo-10k/buildWithParameters"
        ))
        self.assertTrue(gateway.is_public_build_path(
            "/job/open-metadata-sync-demo-replay/build"
        ))
        self.assertFalse(gateway.is_public_build_path("/job/seed/build"))
        self.assertFalse(gateway.is_public_build_path("/script"))

    def test_public_parameters_are_server_owned_and_bounded(self):
        encoded = gateway.normalized_build_parameters(
            "/job/open-metadata-sync-demo-10k/buildWithParameters",
            b"DEMO_SCENARIO=INITIAL&CHUNK_SIZE=2000&REQUEST_ID=attacker",
            now_ms=1_728_000_000_000,
        )
        params = parse_qs(encoded.decode())

        self.assertEqual(params["DEMO_SCENARIO"], ["INITIAL"])
        self.assertEqual(params["CHUNK_SIZE"], ["2000"])
        self.assertEqual(params["SEED"], ["20260809"])
        self.assertEqual(params["REQUEST_ID"], ["public-1728000000000"])

        replay = parse_qs(gateway.normalized_build_parameters(
            "/job/open-metadata-sync-demo-replay/build",
            b"MODE=BACKFILL&CHUNK_SIZE=999999999",
            now_ms=1_728_000_000_001,
        ).decode())
        self.assertEqual(replay["MODE"], ["REPLAY_ERRORS"])
        self.assertEqual(replay["CHUNK_SIZE"], ["1000"])
        self.assertEqual(replay["HIBERNATE_BATCH_SIZE"], ["1000"])
        self.assertEqual(
            replay["SOURCE_EXECUTION_ID"],
            ["00000000-0000-0000-0000-00000000d001"],
        )

    def test_invalid_10k_choices_are_rejected(self):
        with self.assertRaises(ValueError):
            gateway.normalized_build_parameters(
                "/job/open-metadata-sync-demo-10k/build",
                b"DEMO_SCENARIO=DELETE&CHUNK_SIZE=100",
                now_ms=1,
            )
        with self.assertRaises(ValueError):
            gateway.normalized_build_parameters(
                "/job/open-metadata-sync-demo-10k/build",
                b"DEMO_SCENARIO=INITIAL&CHUNK_SIZE=999999",
                now_ms=1,
            )

    def test_admission_allows_at_most_one_pending_or_running_build(self):
        state = gateway.AdmissionState(reservation_seconds=10)

        self.assertTrue(state.try_admit(queue_count=0, busy_executors=0, now=100))
        self.assertFalse(state.try_admit(queue_count=0, busy_executors=0, now=101))
        self.assertFalse(state.try_admit(queue_count=1, busy_executors=0, now=120))
        self.assertFalse(state.try_admit(queue_count=0, busy_executors=1, now=120))
        self.assertTrue(state.try_admit(queue_count=0, busy_executors=0, now=120))

    def test_crumb_headers_bind_request_to_jenkins_session(self):
        headers = gateway.crumb_headers_from_response(
            {"crumbRequestField": "Jenkins-Crumb", "crumb": "abc123"},
            [("Set-Cookie", "JSESSIONID.demo=session-1; Path=/; Secure; HttpOnly")],
        )

        self.assertEqual(headers["Jenkins-Crumb"], "abc123")
        self.assertEqual(headers["Cookie"], "JSESSIONID.demo=session-1")


if __name__ == "__main__":
    unittest.main()
