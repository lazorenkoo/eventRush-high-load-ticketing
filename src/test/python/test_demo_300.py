import concurrent.futures
import importlib.util
import json
import pathlib
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "demo-300.py"
SPEC = importlib.util.spec_from_file_location("demo_300", SCRIPT)
DEMO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEMO)


class Demo300Test(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name) / "demo.json"
        self.calls = []
        self.numbers = []
        self.status = "DRAFT"
        self.uncertain = False

    def client(self, service, method, path, body=None):
        self.calls.append((service, method, path))
        if path == "/api/v1/admin/gates":
            return {"id": "gate"}
        if path == "/api/v1/admin/concerts":
            return {"id": "concert"}
        if path.endswith("/stats"):
            return {"totalSeats": len(self.numbers), "status": self.status}
        if path.endswith("/seats"):
            self.assertEqual(len(body["seats"]), 100)
            self.numbers.extend(seat["number"] for seat in body["seats"])
            if self.uncertain:
                self.uncertain = False
                raise TimeoutError("Response lost after server committed batch")
        elif service == "eventrush" and path.endswith("/open"):
            self.status = "ON_SALE"
        return None

    def setup(self):
        return DEMO.setup(self.path, self.client)

    def test_three_batches_have_unique_global_numbers_and_repeat_preserves_ids(self):
        first = self.setup()
        self.assertEqual(self.numbers, list(range(1, 301)))
        self.assertTrue(first["ready"])
        mutations = [call for call in self.calls if call[1] == "POST"]
        self.assertEqual(self.setup(), first)
        self.assertEqual([call for call in self.calls if call[1] == "POST"], mutations)
        self.assertEqual(json.loads(self.path.read_text()), first)

    def test_lost_batch_response_resumes_from_authoritative_inventory(self):
        self.uncertain = True
        with self.assertRaises(TimeoutError):
            self.setup()
        self.assertEqual(len(self.numbers), 100)
        self.assertEqual(json.loads(self.path.read_text())["concert"], "concert")
        self.setup()
        self.assertEqual(self.numbers, list(range(1, 301)))
        self.assertEqual(sum(path.endswith("/seats") for _, _, path in self.calls), 3)

    def test_unexpected_inventory_is_preserved_without_mutation(self):
        self.path.write_text(json.dumps({"gate": "gate", "concert": "concert"}))
        self.numbers = list(range(1, 51))
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            self.setup()
        self.assertEqual(len(self.numbers), 50)
        self.assertTrue(all(method == "GET" for _, method, _ in self.calls))

    def test_missing_seats_after_open_cannot_be_silently_added(self):
        self.path.write_text(json.dumps({"gate": "gate", "concert": "concert"}))
        self.numbers = list(range(1, 101))
        self.status = "ON_SALE"
        with self.assertRaisesRegex(ValueError, "after sale opening"):
            self.setup()
        self.assertEqual(len(self.numbers), 100)

    def test_existing_sale_and_sold_out_demo_are_never_reset_or_reopened(self):
        self.setup()
        for status in ("ON_SALE", "SOLD_OUT"):
            self.status = status
            self.calls.clear()
            self.setup()
            self.assertEqual(self.status, status)
            self.assertTrue(all(method == "GET" for _, method, _ in self.calls))

    def test_concurrent_setup_calls_are_serialized_by_state_lock(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.setup(), range(2)))
        self.assertEqual(results[0], results[1])
        self.assertEqual(self.numbers, list(range(1, 301)))


if __name__ == "__main__":
    unittest.main()
