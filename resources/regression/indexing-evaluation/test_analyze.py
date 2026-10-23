import math
import unittest
from analyze import norm, rank_metrics, score


class EvaluationMetricsTest(unittest.TestCase):
    def test_document_ranking_deduplicates_and_keeps_original_order(self):
        result = rank_metrics(['noise', 'noise', 'gold'], {'gold': 3})
        self.assertEqual(.5, result['mrr'])
        self.assertEqual(1, result['recall'])
        self.assertAlmostEqual(1 / math.log2(3), result['ndcg'])

    def test_unanswerable_has_no_retrieval_denominator(self):
        self.assertIsNone(rank_metrics(['noise'], {})['recall'])

    def test_exact_evidence_must_belong_to_expected_document(self):
        case = {'id': 'q', 'sourceKind': 'synthetic', 'split': 'test', 'answerable': True,
                'qrels': {'gold': 3}, 'requiredEvidence': [{'docKey': 'gold', 'quote': '等待 37 分钟。'}], 'answerChecks': []}
        row = {'response': {'answerStatus': 'SUCCESS', 'answer': '等待三十七分钟。',
                'finalContext': '等待37分钟。', 'contextUnits': [{'chunk': {'docId': 'wrong', 'text': '等待37分钟。'}}]}}
        result = score(case, row, {'wrong': 'noise'})
        self.assertFalse(result['allEvidence'])
        self.assertIsNone(result['answerCheckProxy'])

    def test_rendering_punctuation_does_not_change_evidence(self):
        self.assertEqual(norm('等待 **37** 分钟。'), norm('等待37分钟'))

    def test_insufficient_information_is_an_abstention(self):
        case = {'id': 'q', 'sourceKind': 'synthetic', 'split': 'test', 'answerable': False}
        row = {'response': {'answerStatus': 'SUCCESS', 'answer': '当前可用信息不足以回答该问题。'}}
        self.assertTrue(score(case, row, {})['answerCheckProxy'])

    def test_failed_retrieval_cannot_masquerade_as_a_healthy_abstention(self):
        case = {'id': 'q', 'sourceKind': 'synthetic', 'split': 'test', 'answerable': False}
        row = {'response': {'answerStatus': 'NO_CONTEXT', 'subQuestionResults': [{'status': 'ERROR', 'stages': []}]}}
        self.assertFalse(score(case, row, {})['healthy'])


if __name__ == '__main__':
    unittest.main()
