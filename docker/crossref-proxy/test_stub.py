import unittest

import stub


class CrossrefStubTest(unittest.TestCase):
    def test_ten_pages_have_exactly_ten_thousand_unique_works(self):
        dois = []
        for page in range(1, 11):
            payload = stub.page_payload(page)
            self.assertEqual(payload["message"]["items-per-page"], 1_000)
            dois.extend(item["DOI"] for item in payload["message"]["items"])

        self.assertEqual(len(dois), 10_000)
        self.assertEqual(len(set(dois)), 10_000)
        self.assertEqual(stub.page_payload(11)["message"]["items"], [])

    def test_cursor_sequence_is_bounded(self):
        self.assertEqual(stub.page_for_cursor("*"), 1)
        self.assertEqual(stub.page_for_cursor("stub-2"), 2)
        self.assertEqual(stub.page_for_cursor("stub-11"), 11)
        for invalid in ("", "stub-1", "stub-12", "other-2"):
            with self.subTest(cursor=invalid), self.assertRaises(ValueError):
                stub.page_for_cursor(invalid)


if __name__ == "__main__":
    unittest.main()
