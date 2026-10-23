"""Create or resume a separate 300-seat demo. Does not start a load run."""
import datetime
import fcntl
import json
import os
import pathlib
import urllib.request


def request(service, method, path, body=None):
    crowdgate = service == "crowdgate"
    origin = "http://crowdgate:8080" if crowdgate else "http://eventrush:8082"
    key = os.environ["CG_ADMIN_KEY" if crowdgate else "EVENTRUSH_ADMIN_API_KEY"]
    headers = {"X-Admin-Key": key, "Origin": "http://localhost:8082", "Content-Type": "application/json"}
    data = None if body is None else json.dumps(body).encode("utf-8")
    query = urllib.request.Request(origin + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(query, timeout=5) as response:
        raw = response.read(65537)
        if len(raw) > 65536:
            raise ValueError("Setup response exceeds bound")
        return json.loads(raw) if raw else None


def setup(state_file, client=request):
    state_file = pathlib.Path(state_file)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    with state_file.with_suffix(".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}

        def save():
            temporary = state_file.with_suffix(".tmp")
            temporary.write_text(json.dumps(state), encoding="utf-8")
            temporary.replace(state_file)

        if "gate" not in state:
            gate = client("crowdgate", "POST", "/api/v1/admin/gates", {
                "name": "EventRush 300 seats", "admissionRate": 5, "maxActiveUsers": 100,
                "sessionTtlSeconds": 60, "tokenTtlSeconds": 60})
            state["gate"] = gate["id"]
            save()
        if "concert" not in state:
            now = datetime.datetime.now(datetime.timezone.utc)
            concert = client("eventrush", "POST", "/api/v1/admin/concerts", {
                "name": "EventRush 300 seats", "startsAt": (now + datetime.timedelta(days=7)).isoformat(),
                "saleStartsAt": (now - datetime.timedelta(minutes=1)).isoformat(), "crowdgateGateId": state["gate"]})
            state["concert"] = concert["id"]
            save()
        base = "/api/v1/admin/concerts/" + state["concert"]
        stats = client("eventrush", "GET", base + "/stats")
        count = stats["totalSeats"]
        if count not in (0, 100, 200, 300):
            raise ValueError("Demo inventory changed; existing seats preserved")
        if count < 300 and stats["status"] != "DRAFT":
            raise ValueError("Cannot add missing seats after sale opening")
        for start in range(count + 1, 301, 100):
            seats = [{"section": "A", "row": str((number - 1) // 20 + 1), "number": number, "price": 25}
                     for number in range(start, start + 100)]
            client("eventrush", "POST", base + "/seats", {"seats": seats})
        stats = client("eventrush", "GET", base + "/stats")
        if stats["totalSeats"] != 300:
            raise ValueError("Demo inventory verification failed")
        if stats["status"] == "DRAFT":
            client("eventrush", "POST", base + "/open")
        if not state.get("ready"):
            client("crowdgate", "POST", "/api/v1/admin/gates/" + state["gate"] + "/open")
            state["ready"] = True
            save()
        return state


if __name__ == "__main__":
    result = setup(os.environ.get("EVENTRUSH_DEMO_300_STATE", "/state/demo-300.json"))
    print("300-seat concert: " + result["concert"])
