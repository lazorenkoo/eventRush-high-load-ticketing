# Load checks

Run commands from the repository root in PowerShell 7. Keep the demo stack and its volumes; each smoke fixture creates a separate concert.

## Prepare and inspect

```powershell
$env:COMPOSE_PROJECT_NAME = 'eventrush-preview'
pwsh scripts/demo.ps1
docker compose -p eventrush-preview run --rm --no-deps -v "${PWD}/scripts:/checks:ro" sample-data python /checks/prepare-k6-smoke.py
```

Save the returned concert ID and seat IDs. A fixture contains eleven seats, so competing for one seat does not close the entire concert when that seat sells.

```powershell
$concertId = '<returned concert UUID>'
$seatId = '<one returned seat UUID>'
docker run --rm -v "${PWD}/load:/scripts:ro" grafana/k6:1.3.0 inspect -e "CONCERT_ID=$concertId" /scripts/buyers.js
```

`inspect` loads the scenario without issuing HTTP requests. Confirm all services are healthy, the concert is ON_SALE, its gate is open, and no unrelated test is running before executing traffic.

## Explicit small run

```powershell
docker run --rm --network eventrush-preview_default -v "${PWD}/load:/scripts:ro" grafana/k6:1.3.0 run -e BASE_URL=http://gateway -e ORIGIN=http://localhost:8082 -e "CONCERT_ID=$concertId" -e "SEAT_IDS=$seatId" -e CLIENTS=10 -e VUS=2 /scripts/buyers.js
```

CLIENTS is the total number of buyer iterations, not simultaneous connections. VUS limits simultaneous buyers to 1..100. Presets are 1, 10, 1000, 10000 and 50000. Each iteration has its own k6 cookie lifecycle and follows CrowdGate admission, participant registration, reservation and simulated payment.

Ten buyers competing for one available seat should produce one purchase and nine unavailable outcomes. Use a fresh seat when checking another successful purchase. Failed checks must stop escalation; inspect their cause before increasing traffic.

10000 and 50000 require `ALLOW_LARGE=YES`. Set MAX_DURATION for the chosen admission rate and client count; the default is 30m. These presets do not start automatically. A configured preset is not evidence that its full workload has been executed or that 10000 simultaneous buyers were tested.

Direct k6 visitors use the public manual-participant flow. Uploaded CSV runs use the bounded server runner and are recorded as TEST. Keep these scopes distinct when reading the activity journal and aggregate queue statistics.

## Inventory invariants

```powershell
Get-Content load/check-invariants.sql -Raw | docker exec -i eventrush-preview-eventrush-db-1 psql -U eventrush -d eventrush -v "concert_id=$concertId"
if ($LASTEXITCODE -ne 0) { throw 'Inventory invariants failed' }
```

The query runs read-only against one concert. It checks the seat-state partition, capacity, duplicate active/confirmed reservations, sold-seat payment proof, and reservation/seat consistency. `invariants_passed = 1` means those checks passed at that snapshot. A missing concert or failed invariant returns a nonzero psql exit status.

## CSV and service checks

```powershell
python scripts/generate-clients.py --help
docker compose -p eventrush-preview run --rm --no-deps --env-from-file .env -v "${PWD}/scripts:/checks:ro" sample-data python /checks/check-delivery-history.py
```

The delivery check creates a separate purchase, verifies ticket/accounting/notification deliveries and attempts, retries a controlled failed receiver, checks unchanged sales, then disables that receiver. Keep `.env` private.

CSV import creates a READY run. Starting requires the explicit `eventrush.load.enabled` and CrowdGate integration flags plus an authenticated administrative Start request. Stop cancels pending clients and interrupts active workers; inspect run progress until STOPPED. Importing a file alone never starts buyers.

Queue counters are aggregate CrowdGate measurements across gates. Run progress counts are scoped to one TEST run. Pending runner clients have not necessarily joined CrowdGate yet.
