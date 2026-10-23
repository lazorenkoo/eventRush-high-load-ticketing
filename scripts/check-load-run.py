"""Verify four competing buyers and one stopped buyer through the real dispatcher."""
import datetime
import importlib.util
import json
import os
import pathlib
import urllib.request
import uuid


spec = importlib.util.spec_from_file_location("purchase_check", pathlib.Path(__file__).with_name("check-purchase.py"))
purchase = importlib.util.module_from_spec(spec)
spec.loader.exec_module(purchase)
request = purchase.request
eventually = purchase.eventually


def upload(admin, seats):
    boundary = "check-" + uuid.uuid4().hex
    csv = "firstName,lastName,seatNumber\n" + "".join("Test,Buyer," + str(seat) + "\n" for seat in seats)
    body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="check.csv"\r\n'
            + "Content-Type: text/csv\r\n\r\n" + csv + "\r\n--" + boundary + "--\r\n").encode("utf-8")
    headers = {"Origin": purchase.ORIGIN, "X-Admin-Key": os.environ["EVENTRUSH_ADMIN_API_KEY"],
               "Content-Type": "multipart/form-data; boundary=" + boundary}
    query = urllib.request.Request(purchase.BASE + admin + "/load-runs?concurrency=2",
                                   data=body, headers=headers, method="POST")
    with purchase.browser.open(query, timeout=5) as response:
        assert response.status == 201, "Import rejected"
        value = json.loads(response.read(65536))
        assert value["status"] == "READY" and value["participantCount"] == len(seats), "Invalid import"
        return admin + "/load-runs/" + value["id"]


def progress(path):
    value = request("GET", path + "/progress", admin=True)
    assert value["scope"] == "TEST_RUN", "Incorrect measurement scope"
    total = sum(value[key] for key in ("pending", "running", "succeeded", "unavailable", "failed", "cancelled"))
    assert total == value["participantCount"], "Client counters do not add up"
    assert value["running"] <= value["concurrency"], "Run concurrency exceeded"
    return value


def main():
    now = datetime.datetime.now(datetime.timezone.utc)
    gate = request("POST", "/api/v1/admin/gates", {
        "name": "Dispatcher verification", "admissionRate": 2, "maxActiveUsers": 10,
        "sessionTtlSeconds": 300, "tokenTtlSeconds": 60}, admin=True, crowdgate=True)["id"]
    concert = request("POST", "/api/v1/admin/concerts", {
        "name": "Dispatcher verification", "startsAt": (now + datetime.timedelta(days=1)).isoformat(),
        "saleStartsAt": (now - datetime.timedelta(minutes=1)).isoformat(), "crowdgateGateId": gate}, admin=True)["id"]
    admin = "/api/v1/admin/concerts/" + concert
    request("POST", admin + "/seats", {"seats": [
        {"section": "CHECK", "row": "1", "number": seat, "price": 25} for seat in (1, 2, 3)]}, admin=True)
    request("POST", admin + "/open", admin=True)
    gate_path = "/api/v1/admin/gates/" + gate
    request("POST", gate_path + "/open", admin=True, crowdgate=True)
    path = upload(admin, [1, 1, 2, 2])
    complete = False
    try:
        assert request("POST", path + "/start", admin=True)["status"] == "RUNNING", "Start rejected"

        def finished():
            value = progress(path)
            return value if value["status"] == "COMPLETED" else None

        result = eventually(finished, "Dispatcher completion")
        assert result["succeeded"] == 2 and result["unavailable"] == 2, "Seat competition mismatch"
        assert result["failed"] == 0 and result["cancelled"] == 0, "Unexpected failed clients"
        stats = request("GET", admin + "/stats", admin=True)
        assert stats["sold"] == 2 and stats["revenue"] == 50, "Duplicate sale or wrong revenue"
        complete = True
    finally:
        if not complete:
            request("POST", path + "/stop", admin=True)
    request("POST", gate_path + "/pause", admin=True, crowdgate=True)
    stopped_path = upload(admin, [3])
    try:
        request("POST", stopped_path + "/start", admin=True)
        eventually(lambda: progress(stopped_path)["running"] == 1, "Waiting client claim")
        assert request("POST", stopped_path + "/stop", admin=True)["status"] == "STOPPING", "Stop rejected"

        def stopped():
            value = progress(stopped_path)
            return value if value["status"] == "STOPPED" else None

        result = eventually(stopped, "Dispatcher stop")
        assert result["cancelled"] == 1 and result["running"] == 0 and result["failed"] == 0, "Cancellation mismatch"
        assert request("POST", stopped_path + "/stop", admin=True)["status"] == "STOPPED", "Stop not idempotent"
    finally:
        request("POST", stopped_path + "/stop", admin=True)
    print(json.dumps({"result": "PASS", "concertId": concert,
                      "verified": "CSV, start, bounded progress, two sales, two conflicts, waiting client stop"}))


if __name__ == "__main__":
    main()
