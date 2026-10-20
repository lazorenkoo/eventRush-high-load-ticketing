import datetime
import fcntl
import json
import os
import pathlib
import time
import urllib.error
import urllib.request


DIRECTORY = pathlib.Path("/state")
DIRECTORY.mkdir(exist_ok=True)
lock = (DIRECTORY / "setup.lock").open("w")
fcntl.flock(lock, fcntl.LOCK_EX)
state_file = DIRECTORY / "sample.json"
state = json.loads(state_file.read_text()) if state_file.exists() else {}


def save():
    temporary = state_file.with_suffix(".tmp")
    temporary.write_text(json.dumps(state), encoding="utf-8")
    temporary.replace(state_file)


def request(service, method, path, body=None):
    key = os.environ["CG_ADMIN_KEY" if service == "crowdgate" else "EVENTRUSH_ADMIN_API_KEY"]
    headers = {"X-Admin-Key": key, "Origin": "http://localhost:8082", "Content-Type": "application/json"}
    data = None if body is None else json.dumps(body).encode("utf-8")
    query = urllib.request.Request("http://" + service + ":" + ("8080" if service == "crowdgate" else "8082")
                                   + path, headers=headers, data=data, method=method)
    with urllib.request.urlopen(query, timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


for service, path in (("crowdgate", "/actuator/health"), ("eventrush", "/api/v1/concerts")):
    for attempt in range(30):
        try:
            request(service, "GET", path)
            break
        except (urllib.error.URLError, TimeoutError):
            if attempt == 29:
                raise SystemExit("Sample setup could not reach " + service) from None
            time.sleep(2)

if "gate" not in state:
    gate = request("crowdgate", "POST", "/api/v1/admin/gates", {
        "name": "EventRush demo", "admissionRate": 5, "maxActiveUsers": 20,
        "sessionTtlSeconds": 300, "tokenTtlSeconds": 60})
    state["gate"] = gate["id"]
    save()
if "concert" not in state:
    now = datetime.datetime.now(datetime.timezone.utc)
    concert = request("eventrush", "POST", "/api/v1/admin/concerts", {
        "name": "EventRush live demo", "startsAt": (now + datetime.timedelta(days=7)).isoformat(),
        "saleStartsAt": (now - datetime.timedelta(minutes=1)).isoformat(), "crowdgateGateId": state["gate"]})
    state["concert"] = concert["id"]
    save()
if not state.get("ready"):
    base = "/api/v1/admin/concerts/" + state["concert"]
    stats = request("eventrush", "GET", base + "/stats")
    if stats["totalSeats"] == 0:
        seats = [{"section": "A", "row": str(index // 10 + 1), "number": index % 10 + 1, "price": 25}
                 for index in range(50)]
        request("eventrush", "POST", base + "/seats", {"seats": seats})
    elif stats["totalSeats"] != 50:
        raise SystemExit("Sample inventory changed; preserving existing seats")
    request("eventrush", "POST", base + "/open")
    request("crowdgate", "POST", "/api/v1/admin/gates/" + state["gate"] + "/open")
    state["ready"] = True
    save()
print("Sample concert: " + state["concert"])
