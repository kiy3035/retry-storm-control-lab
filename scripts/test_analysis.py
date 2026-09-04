import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("analysis", Path(__file__).with_name("analyze-experiment.py"))
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def fixture():
    return {
        "trialId": "test", "policy": "FIXED_200", "repetition": 1,
        "messages": [{"messageId": "synthetic", "state": "SUCCEEDED", "failuresBeforeSuccess": 2,
                      "attemptCount": 3, "publishedAt": "2026-01-01T00:00:00Z",
                      "attemptTimestamps": ["2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z",
                                            "2026-01-01T00:00:02Z"],
                      "completedAt": "2026-01-01T00:00:03Z"}],
        "dlq": [], "replays": [], "dbCallsBefore": 1, "dbCallsAfter": 3, "dbObservationSeconds": 2,
        "telemetry": {"processCpu": [[0, "0.1"], [1, "0.2"]], "queueDepth": [[0, "1"], [1, "0"]]},
    }


class AnalysisTest(unittest.TestCase):
    def test_nearest_rank_and_even_median(self):
        result = analysis.stats(list(range(1, 101)))
        self.assertEqual(result["median"], 50.5)
        self.assertEqual(result["p95"], 95)
        self.assertEqual(result["p99"], 99)

    def test_empty_and_nan_rejected(self):
        for values in ([], [float("nan")]):
            with self.assertRaises(ValueError):
                analysis.stats(values)

    def test_bucket_boundaries_include_zero_and_exclude_first_attempt(self):
        row, buckets = analysis.summarize(fixture())
        self.assertEqual(buckets["widthMsToCounts"]["1000"], [0, 1, 1, 0])
        self.assertEqual(row["retries"], 2)
        self.assertEqual(row["completionMedianMs"], 3000)
        self.assertEqual(row["appDbCallsPerSecond"], 1)

    def test_wrong_attempt_budget_rejected(self):
        value = fixture()
        value["messages"][0]["attemptCount"] = 2
        with self.assertRaises(ValueError):
            analysis.summarize(value)

    def test_inverted_clock_rejected(self):
        value = fixture()
        value["messages"][0]["completedAt"] = "2025-01-01T00:00:00Z"
        with self.assertRaises(ValueError):
            analysis.summarize(value)

    def test_duplicate_message_rejected(self):
        value = fixture()
        value["messages"].append(copy.deepcopy(value["messages"][0]))
        with self.assertRaises(ValueError):
            analysis.summarize(value)

    def test_missing_dlq_rejected(self):
        value = fixture()
        value["messages"][0].update(state="FAILED", failuresBeforeSuccess=3)
        with self.assertRaises(ValueError):
            analysis.summarize(value)

    def test_nonterminal_state_rejected(self):
        value = fixture()
        value["messages"][0]["state"] = "PROCESSING"
        with self.assertRaises(ValueError):
            analysis.summarize(value)


if __name__ == "__main__":
    unittest.main()
