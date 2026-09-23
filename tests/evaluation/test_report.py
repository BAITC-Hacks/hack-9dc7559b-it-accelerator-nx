"""Quality metrics must count failed answers and wrong authoritative numbers."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("evaluation", ROOT / "scripts/evaluate.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class ReportTest(unittest.TestCase):
    def test_failed_retrieval_is_in_denominator_and_does_not_inflate_heldout(self):
        cases = [{"status": "completed", "split": "tuning", "recallAt5": 1., "latencyMs": 10},
                 {"status": "failed", "split": "heldout", "recallAt5": 0., "latencyMs": 20},
                 {"status": "completed", "split": "heldout", "recallAt5": None, "latencyMs": 30}]
        result = evaluation.summarize(cases, [])
        self.assertEqual(result["recallAt5"], {"all": .5, "tuning": 1., "heldout": 0.})
        self.assertIsNone(result["groundedRubricScore"])

    def test_unknown_and_ineligible_stock_or_wrong_price_fail(self):
        products = [{"article": "000001", "price": "1500", "currency": "KZT", "warehouses": [
            {"warehouseId": "ALA", "eligible": True, "status": "IN_STOCK", "availableQuantity": "12"},
            {"warehouseId": "CLOSED", "eligible": False, "status": "IN_STOCK", "availableQuantity": "999"}]}]
        offers = [{"article": "000001", "price": {"amount": price, "currency": "KZT"},
                   "warehouse": warehouse, "available": {"value": quantity}}
                  for price, warehouse, quantity in [("1500", "ALA", "12"), ("1499", "ALA", "12"), ("1500", "CLOSED", "999")]]
        result = evaluation.summarize([{"status": "completed", "split": "tuning", "latencyMs": 1,
                                        "offers": offers, "recallAt5": 1}], products)
        self.assertEqual(result["authoritativeOfferAssertions"], {"checked": 3, "passed": 1})


if __name__ == "__main__":
    unittest.main()
