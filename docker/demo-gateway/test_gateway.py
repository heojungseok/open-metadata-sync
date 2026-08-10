import unittest
import json
from email.message import Message
from unittest.mock import patch
from urllib.parse import parse_qs, urlencode

import gateway


class GatewayContractTest(unittest.TestCase):
    def test_only_the_unified_crossref_build_path_is_accepted(self):
        self.assertTrue(gateway.is_public_build_path(
            "/job/open-metadata-sync-demo/buildWithParameters"
        ))
        self.assertFalse(gateway.is_public_build_path("/job/open-metadata-sync-demo-10k/build"))
        self.assertFalse(gateway.is_public_build_path("/job/open-metadata-sync-demo-replay/build"))
        self.assertFalse(gateway.is_public_build_path(
            "/job/open-metadata-sync-demo-crossref/buildWithParameters"
        ))
        self.assertFalse(gateway.is_public_build_path("/job/seed/build"))
        self.assertFalse(gateway.is_public_build_path("/script"))

    def test_build_with_parameters_is_flat_server_owned_mode_aware_and_bounded(self):
        normalized = gateway.normalized_build_request(
            "/job/open-metadata-sync-demo/buildWithParameters",
            b"MODE=BACKFILL&CHUNK_SIZE=2000",
            now_ms=1_728_000_000_000,
            random_token="a1b2c3d4",
        )
        params = parse_qs(normalized.body.decode())

        self.assertEqual(params["MODE"], ["BACKFILL"])
        self.assertEqual(params["CHUNK_SIZE"], ["2000"])
        self.assertEqual(params["REQUEST_ID"], ["public-1728000000000-a1b2c3d4"])
        self.assertEqual(normalized.request_id, "public-1728000000000-a1b2c3d4")
        self.assertEqual(normalized.mode, "BACKFILL")

        replay_form = parse_qs(gateway.normalized_build_request(
            "/job/open-metadata-sync-demo/build",
            structured_form(gateway.PARAMETER_NAMES, {"MODE": "REPLAY_ERRORS", "CHUNK_SIZE": "2000"}),
            now_ms=1_728_000_000_001,
            random_token="d4c3b2a1",
        ).body.decode())
        replay_envelope = json.loads(replay_form["json"][0])
        replay = {item["name"]: [item["value"]] for item in replay_envelope["parameter"]}
        self.assertEqual(replay["MODE"], ["REPLAY_ERRORS"])
        self.assertEqual(replay["CHUNK_SIZE"], ["1000"])
        self.assertNotIn("SOURCE_EXECUTION_ID", replay)

    def test_build_accepts_one_json_envelope_and_ignores_mirror_values(self):
        body = structured_form(
            gateway.PARAMETER_NAMES,
            {"REQUEST_ID": "attacker", "MODE": "BACKFILL", "CHUNK_SIZE": "500"},
            mirror_values=("wrong-name", "wrong-mode", "999999"),
        )

        normalized = gateway.normalized_build_request(
            "/job/open-metadata-sync-demo/build?delay=0sec",
            body,
            now_ms=7,
            random_token="01234567",
        )
        envelope = json.loads(parse_qs(normalized.body.decode())["json"][0])
        parameters = {item["name"]: item["value"] for item in envelope["parameter"]}

        self.assertEqual(parameters, {
            "REQUEST_ID": "public-7-01234567",
            "MODE": "BACKFILL",
            "CHUNK_SIZE": "500",
        })

    def test_actual_jenkins_browser_form_discards_ui_metadata_and_client_crumb(self):
        envelope = {
            "parameter": [
                {"name": "REQUEST_ID", "value": ""},
                {"name": "MODE", "value": "BACKFILL"},
                {"name": "CHUNK_SIZE", "value": "1000"},
            ],
            "statusCode": "303",
            "redirectTo": ".",
            "": "",
            "Jenkins-Crumb": "browser-owned-crumb",
        }
        body = urlencode((
            ("name", "REQUEST_ID"), ("value", ""),
            ("name", "MODE"), ("value", "BACKFILL"),
            ("name", "CHUNK_SIZE"), ("value", "1000"),
            ("statusCode", "303"), ("redirectTo", "."),
            ("Jenkins-Crumb", "browser-owned-crumb"),
            ("json", json.dumps(envelope, separators=(",", ":"))),
        )).encode()

        normalized = gateway.normalized_build_request(
            "/job/open-metadata-sync-demo/build?delay=0sec",
            body,
            now_ms=9,
            random_token="89abcdef",
        )
        parameters = {
            item["name"]: item["value"]
            for item in json.loads(parse_qs(normalized.body.decode())["json"][0])["parameter"]
        }

        self.assertEqual(parameters, {
            "REQUEST_ID": "public-9-89abcdef",
            "MODE": "BACKFILL",
            "CHUNK_SIZE": "1000",
        })

    def test_mixed_duplicate_and_malformed_inputs_are_rejected(self):
        valid = structured_form(gateway.PARAMETER_NAMES, {"MODE": "BACKFILL", "CHUNK_SIZE": "100"})
        duplicate_json = valid + b"&json=%7B%7D"

        invalid_requests = (
            ("/job/open-metadata-sync-demo/build", duplicate_json),
            ("/job/open-metadata-sync-demo/build", valid + b"&CHUNK_SIZE=500"),
            ("/job/open-metadata-sync-demo/build?delay=1sec", valid),
            ("/job/open-metadata-sync-demo/buildWithParameters", b"MODE=BACKFILL&CHUNK_SIZE=100&CHUNK_SIZE=500"),
            ("/job/open-metadata-sync-demo/buildWithParameters", b"REQUEST_ID=attacker&MODE=BACKFILL&CHUNK_SIZE=100"),
            ("/job/open-metadata-sync-demo/buildWithParameters?delay=0sec", b"MODE=BACKFILL&CHUNK_SIZE=100"),
            ("/job/open-metadata-sync-demo/build", b"json=%FF"),
            ("/job/open-metadata-sync-demo/build", structured_form(
                gateway.PARAMETER_NAMES, {"MODE": "BACKFILL", "CHUNK_SIZE": "100"},
                raw_json='{"parameter":[],"parameter":[]}',
            )),
        )
        for path, body in invalid_requests:
            with self.subTest(path=path, body=body):
                with self.assertRaises(ValueError):
                    gateway.normalized_build_request(path, body, now_ms=1, random_token="token")

    def test_invalid_mode_and_chunk_choices_are_rejected(self):
        for body in (b"MODE=DELETE&CHUNK_SIZE=1000", b"MODE=BACKFILL&CHUNK_SIZE=999999"):
            with self.subTest(body=body), self.assertRaises(ValueError):
                gateway.normalized_build_request(
                    "/job/open-metadata-sync-demo/buildWithParameters",
                    body, now_ms=1, random_token="token",
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

    def test_latest_terminal_backfill_attempt_consumes_cooldown_without_replay_masking_it(self):
        builds = [
            build("REPLAY_ERRORS", "NOT_BUILT", 180_000, 1_000),
            build("BACKFILL", "ABORTED", 100_000, 5_000),
        ]

        self.assertEqual(gateway.backfill_cooldown_remaining_seconds(builds, now_ms=205_000), 200)
        self.assertEqual(gateway.backfill_cooldown_remaining_seconds(builds, now_ms=405_000), 0)
        self.assertEqual(gateway.backfill_cooldown_remaining_seconds([], now_ms=205_000), 0)
        self.assertEqual(gateway.request_cooldown_seconds("BACKFILL", 200), 200)
        self.assertEqual(gateway.request_cooldown_seconds("REPLAY_ERRORS", 200), 0)

    def test_backend_history_is_not_truncated_before_mode_aware_cooldown(self):
        paths = []

        def response(path):
            paths.append(path)
            if path.startswith("/queue/"):
                return {"items": []}
            return {"lastBuild": None, "builds": []}

        with patch.object(gateway, "read_json", side_effect=response):
            self.assertEqual(gateway.backend_load(), (0, 0, 0))

        self.assertNotIn("{0,50}", paths[1])

    def test_content_length_is_single_non_negative_bounded_and_not_chunked(self):
        headers = Message()
        headers["Content-Length"] = "10"
        self.assertEqual(gateway.validated_content_length(headers), 10)

        invalid = []
        negative = Message()
        negative["Content-Length"] = "-1"
        invalid.append(negative)
        duplicate = Message()
        duplicate["Content-Length"] = "1"
        duplicate["Content-Length"] = "2"
        invalid.append(duplicate)
        chunked = Message()
        chunked["Transfer-Encoding"] = "chunked"
        invalid.append(chunked)
        oversized = Message()
        oversized["Content-Length"] = "16385"
        invalid.append(oversized)
        for value in invalid:
            with self.assertRaises(ValueError):
                gateway.validated_content_length(value)

    def test_correlation_log_links_ray_and_server_request_id(self):
        self.assertEqual(
            gateway.correlation_log("abc-SIN", "public-7-token", "queued"),
            "ray=abc-SIN request_id=public-7-token queued",
        )


def structured_form(parameter_names, values, mirror_values=None, raw_json=None):
    envelope = {
        "parameter": [
            {"name": name, "value": values.get(name, "")}
            for name in parameter_names
        ]
    }
    fields = [("json", raw_json or json.dumps(envelope, separators=(",", ":"))), ("Submit", "Build")]
    mirrors = mirror_values or tuple(values.get(name, "") for name in parameter_names)
    for name, value in zip(parameter_names, mirrors):
        fields.extend((("name", name), ("value", value)))
    return urlencode(fields).encode()


def build(mode, result, timestamp, duration):
    return {
        "building": False,
        "result": result,
        "timestamp": timestamp,
        "duration": duration,
        "actions": [{"parameters": [{"name": "MODE", "value": mode}]}],
    }


if __name__ == "__main__":
    unittest.main()
