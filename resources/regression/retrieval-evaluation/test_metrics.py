import math
import unittest
from metrics import aggregate, evaluate


class MetricsTest(unittest.TestCase):
    def test_hand_calculated(self):
        result = evaluate(["x", "a", "b"], {"a": 3, "b": 1, "c": 2, "x": 0}, 3)
        self.assertAlmostEqual(2/3, result["recall"])
        self.assertAlmostEqual(2/3, result["precision"])
        self.assertEqual(.5, result["mrr"])
        self.assertAlmostEqual((7/math.log2(3) + .5) / (7 + 3/math.log2(3) + .5), result["ndcg"])

    def test_perfect_and_reversed(self):
        qrels = {"a": 3, "b": 1}
        self.assertEqual(1, evaluate(["a", "b"], qrels, 2)["ndcg"])
        self.assertLess(evaluate(["b", "a"], qrels, 2)["ndcg"], 1)

    def test_duplicate_short_list(self):
        result = evaluate(["a", "a"], {"a": 1, "b": 1}, 5)
        self.assertEqual(.2, result["precision"])
        self.assertEqual(.5, result["recall"])
        self.assertEqual(1, result["duplicates_removed"])

    def test_empty_and_unjudged(self):
        self.assertEqual(0, evaluate([], {"a": 1}, 5)["ndcg"])
        result = evaluate(["unknown"], {"a": 1}, 1)
        self.assertEqual(["unknown"], result["unjudged"])
        self.assertEqual(0, result["judged_fraction"])

    def test_unanswerable_is_not_perfect_recall(self):
        result = evaluate([], {}, 5)
        for metric in ("recall", "mrr", "ndcg"):
            self.assertIsNone(result[metric])
        self.assertEqual(0, aggregate([result])["recall"]["n"])

    def test_invalid_inputs(self):
        for k in (0, -1, True, 1.2):
            with self.assertRaises(ValueError):
                evaluate([], {}, k)
        for grade in (-1, 4, True, .5):
            with self.assertRaises(ValueError):
                evaluate([], {"a": grade}, 5)
