import csv
import importlib.util
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "generate-clients.py"
SPEC = importlib.util.spec_from_file_location("generate_clients", SCRIPT)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class GenerateClientsTest(unittest.TestCase):
    def generate(self, clients=1000, seats=300, seed=42):
        output = io.StringIO()
        GENERATOR.write_csv(output, clients, seats, seed)
        return output.getvalue()

    def test_all_presets_have_exact_header_unique_buyers_and_valid_seats(self):
        for size in (1000, 10000, 50000):
            with self.subTest(size=size):
                text = self.generate(size)
                reader = csv.DictReader(io.StringIO(text))
                rows = list(reader)
                self.assertEqual(reader.fieldnames, ["firstName", "lastName", "seatNumber"])
                self.assertEqual(len(rows), size)
                self.assertEqual(len({(row["firstName"], row["lastName"]) for row in rows}), size)
                self.assertTrue(all(1 <= int(row["seatNumber"]) <= 300 for row in rows))
                self.assertTrue(all(row["firstName"] and row["lastName"] for row in rows))
                self.assertLess(len(text.encode("utf-8")), 16 * 1024 * 1024)

    def test_seed_is_repeatable_and_changes_seat_choices(self):
        self.assertEqual(self.generate(seed=17), self.generate(seed=17))
        self.assertNotEqual(self.generate(seed=17), self.generate(seed=18))

    def test_seat_bounds_include_both_endpoints(self):
        for seats in (1, 300, 10000):
            rows = list(csv.DictReader(io.StringIO(self.generate(seats=seats))))
            self.assertTrue(all(1 <= int(row["seatNumber"]) <= seats for row in rows))
        self.assertEqual({row["seatNumber"] for row in csv.DictReader(io.StringIO(self.generate(seats=1)))}, {"1"})

    def test_invalid_input_is_rejected_before_writing(self):
        cases = [(999, 300), (50001, 300), (1000, 0), (1000, 10001), (1000, True), (1000, 1.5)]
        for clients, seats in cases:
            with self.subTest(clients=clients, seats=seats):
                output = io.StringIO()
                with self.assertRaises(ValueError):
                    GENERATOR.write_csv(output, clients, seats)
                self.assertEqual(output.getvalue(), "")

    def cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, timeout=10)

    def test_cli_creates_valid_utf8_and_never_overwrites_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "clients.csv"
            result = self.cli("--clients", "50000", "--output", str(path))
            self.assertEqual(result.returncode, 0, result.stderr)
            original = path.read_bytes()
            self.assertEqual(len(list(csv.DictReader(io.StringIO(original.decode("utf-8"))))), 50000)
            self.assertEqual(self.cli("--output", str(path)).returncode, 2)
            self.assertEqual(path.read_bytes(), original)

    def test_cli_rejects_bad_options_without_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "clients.csv"
            for option, value in (("--clients", "50001"), ("--seats", "0"), ("--seats", "text")):
                self.assertEqual(self.cli(option, value, "--output", str(path)).returncode, 2)
                self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
