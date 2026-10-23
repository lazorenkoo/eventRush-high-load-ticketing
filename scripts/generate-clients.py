"""Generate deterministic CSV buyers. This command does not send purchase requests."""
import argparse
import csv
import pathlib
import random


PRESETS = (1000, 10000, 50000)
FIRST_NAMES = ("Alex", "Anna", "Ben", "Chloe", "Daniel", "Emma", "Felix", "Grace",
               "Henry", "Isabel", "Jack", "Kate", "Leo", "Mia", "Noah", "Olivia")
LAST_NAMES = ("Smith", "Brown", "Clark", "Davis", "Evans", "Green", "Hall", "King",
              "Lewis", "Miller", "Parker", "Reed", "Scott", "Turner", "Walker", "Young")


def write_csv(output, clients=1000, seats=300, seed=42):
    if clients not in PRESETS:
        raise ValueError("Clients must be 1000, 10000 or 50000")
    if not isinstance(seats, int) or isinstance(seats, bool) or not 1 <= seats <= 10000:
        raise ValueError("Seats must be between 1 and 10000")
    generator = random.Random(seed)
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(("firstName", "lastName", "seatNumber"))
    for index in range(clients):
        first = FIRST_NAMES[index % len(FIRST_NAMES)]
        last = LAST_NAMES[(index // len(FIRST_NAMES)) % len(LAST_NAMES)] + str(index + 1).zfill(5)
        writer.writerow((first, last, generator.randint(1, seats)))


def seat_count(value):
    try:
        number = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("Seats must be an integer") from None
    if not 1 <= number <= 10000:
        raise argparse.ArgumentTypeError("Seats must be between 1 and 10000")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clients", type=int, choices=PRESETS, default=1000)
    parser.add_argument("--seats", type=seat_count, default=300)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    try:
        with args.output.open("x", encoding="utf-8", newline="") as output:
            write_csv(output, args.clients, args.seats, args.seed)
    except OSError as error:
        parser.error(str(error))
    print(f"Created {args.output}: {args.clients} clients, {args.seats} seats")


if __name__ == "__main__":
    main()
