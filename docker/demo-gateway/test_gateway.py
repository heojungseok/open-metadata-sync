import unittest
import json
from urllib.parse import parse_qs, urlencode

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

    def test_build_with_parameters_is_flat_server_owned_and_bounded(self):
        normalized = gateway.normalized_build_request(
            "/job/open-metadata-sync-demo-10k/buildWithParameters",
            b"CHUNK_SIZE=2000",
            now_ms=1_728_000_000_000,
            random_token="a1b2c3d4",
        )
        params = parse_qs(normalized.body.decode())

        self.assertEqual(params["CHUNK_SIZE"], ["2000"])
        self.assertEqual(params["REQUEST_ID"], ["public-1728000000000-a1b2c3d4"])
        self.assertEqual(normalized.request_id, "public-1728000000000-a1b2c3d4")

        replay_form = parse_qs(gateway.normalized_build_request(
            "/job/open-metadata-sync-demo-replay/build",
            structured_form(gateway.REPLAY_PARAMETER_NAMES, {"MODE": "BACKFILL"}),
            now_ms=1_728_000_000_001,
            random_token="d4c3b2a1",
        ).body.decode())
        replay_envelope = json.loads(replay_form["json"][0])
        replay = {item["name"]: [item["value"]] for item in replay_envelope["parameter"]}
        self.assertEqual(replay["MODE"], ["REPLAY_ERRORS"])
        self.assertEqual(replay["CHUNK_SIZE"], ["1000"])
        self.assertEqual(replay["HIBERNATE_BATCH_SIZE"], ["1000"])
        self.assertEqual(
            replay["SOURCE_EXECUTION_ID"],
            ["00000000-0000-0000-0000-00000000d001"],
        )

    def test_build_accepts_one_json_envelope_and_ignores_mirror_values(self):
        body = structured_form(
            ("REQUEST_ID", "CHUNK_SIZE"),
            {"REQUEST_ID": "attacker", "CHUNK_SIZE": "500"},
            mirror_values=("wrong-name", "999999"),
        )

        normalized = gateway.normalized_build_request(
            "/job/open-metadata-sync-demo-10k/build?delay=0sec",
            body,
            now_ms=7,
            random_token="01234567",
        )
        envelope = json.loads(parse_qs(normalized.body.decode())["json"][0])
        parameters = {item["name"]: item["value"] for item in envelope["parameter"]}

        self.assertEqual(parameters, {
            "REQUEST_ID": "public-7-01234567",
            "CHUNK_SIZE": "500",
        })

    def test_mixed_duplicate_and_malformed_inputs_are_rejected(self):
        valid = structured_form(("REQUEST_ID", "CHUNK_SIZE"), {"CHUNK_SIZE": "100"})
        duplicate_json = valid + b"&json=%7B%7D"

        invalid_requests = (
            ("/job/open-metadata-sync-demo-10k/build", duplicate_json),
            ("/job/open-metadata-sync-demo-10k/build", valid + b"&CHUNK_SIZE=500"),
            ("/job/open-metadata-sync-demo-10k/build?delay=1sec", valid),
            ("/job/open-metadata-sync-demo-10k/buildWithParameters", b"CHUNK_SIZE=100&CHUNK_SIZE=500"),
            ("/job/open-metadata-sync-demo-10k/buildWithParameters", b"REQUEST_ID=attacker&CHUNK_SIZE=100"),
            ("/job/open-metadata-sync-demo-10k/buildWithParameters?delay=0sec", b"CHUNK_SIZE=100"),
            ("/job/open-metadata-sync-demo-10k/build", b"json=%FF"),
        )
        for path, body in invalid_requests:
            with self.subTest(path=path, body=body):
                with self.assertRaises(ValueError):
                    gateway.normalized_build_request(path, body, now_ms=1, random_token="token")

    def test_invalid_10k_choices_are_rejected(self):
        with self.assertRaises(ValueError):
            gateway.normalized_build_request(
                "/job/open-metadata-sync-demo-10k/buildWithParameters",
                b"CHUNK_SIZE=999999",
                now_ms=1,
                random_token="token",
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

    def test_terminal_live_build_consumes_a_durable_three_hundred_second_cooldown(self):
        build = {"building": False, "result": "ABORTED", "timestamp": 100_000, "duration": 5_000}

        self.assertEqual(gateway.cooldown_remaining_seconds(build, now_ms=205_000), 200)
        self.assertEqual(gateway.cooldown_remaining_seconds(build, now_ms=405_000), 0)
        self.assertEqual(gateway.cooldown_remaining_seconds(None, now_ms=205_000), 0)

    def test_correlation_log_links_ray_and_server_request_id(self):
        self.assertEqual(
            gateway.correlation_log("abc-SIN", "public-7-token", "queued"),
            "ray=abc-SIN request_id=public-7-token queued",
        )


def structured_form(parameter_names, values, mirror_values=None):
    envelope = {
        "parameter": [
            {"name": name, "value": values.get(name, "")}
            for name in parameter_names
        ]
    }
    fields = [("json", json.dumps(envelope, separators=(",", ":"))), ("Submit", "Build")]
    mirrors = mirror_values or tuple(values.get(name, "") for name in parameter_names)
    for name, value in zip(parameter_names, mirrors):
        fields.extend((("name", name), ("value", value)))
    return urlencode(fields).encode()


if __name__ == "__main__":
    unittest.main()
