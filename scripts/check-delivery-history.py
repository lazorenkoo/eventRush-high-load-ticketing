"""Verify real RelayForge delivery history and an explicit failed receiver retry."""
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import urllib.request


spec = importlib.util.spec_from_file_location("purchase", pathlib.Path(__file__).with_name("check-purchase.py"))
purchase = importlib.util.module_from_spec(spec)
spec.loader.exec_module(purchase)


def relay(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"X-API-Key": os.environ["RELAYFORGE_API_KEY"], "Content-Type": "application/json"}
    query = urllib.request.Request("http://relayforge:8080/api/v1" + path, data=data,
                                   headers=headers, method=method)
    with urllib.request.urlopen(query, timeout=10) as response:
        raw = response.read(65537)
        assert len(raw) <= 65536
        return json.loads(raw)


def main():
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        purchase.main()
    fixture = json.loads(output.getvalue())
    admin = "/api/v1/admin/concerts/" + fixture["concertId"]
    page = purchase.request("GET", admin + "/events", admin=True)
    event = next(row for row in page["items"] if row["type"] == "ticket.purchased")
    path = admin + "/events/" + event["id"] + "/deliveries"
    history = purchase.request("GET", path, admin=True)
    assert len(history["deliveries"]) == 3
    assert all(row["status"] == "SUCCEEDED" for row in history["deliveries"])
    assert "payload" not in history and "lastError" not in json.dumps(history)
    for delivery in history["deliveries"]:
        attempts = purchase.request("GET", path + "/" + str(delivery["id"]) + "/attempts", admin=True)
        assert attempts and all(row["deliveryId"] == delivery["id"] for row in attempts)
        assert "error" not in json.dumps(attempts)
    endpoint = relay("POST", "/endpoints", {
        "url": "http://eventrush:8082/webhooks/check-missing-receiver", "subscribedEvents": []})["id"]
    try:
        relay("POST", "/endpoints/" + str(endpoint) + "/rotate-secret")
        result = relay("POST", "/deliveries", {"eventId": history["relayEventId"], "endpointId": endpoint})
        assert result["successful"] is False and result["httpStatus"] == 404
        before = purchase.request("GET", path, admin=True)["deliveries"]
        failed = next(row for row in before if row["endpointId"] == endpoint and row["status"] == "FAILED")
        retried = purchase.request("POST", path + "/" + str(failed["id"]) + "/retry", admin=True)
        assert retried == {"httpStatus": 404, "successful": False}
        # RelayForge manual retry creates a new delivery; retain its authoritative history model.
        after = purchase.request("GET", path, admin=True)["deliveries"]
        assert any(row["endpointId"] == endpoint and row["id"] not in {item["id"] for item in before}
                   and row["status"] == "FAILED" for row in after)
        stats = purchase.request("GET", admin + "/stats", admin=True)
        assert stats["sold"] == 1 and stats["revenue"] == 25
        print(json.dumps({"result": "PASS", "concertId": fixture["concertId"],
                          "eventId": event["id"], "verified": "delivery history, attempts, retry, unchanged sales"}))
    finally:
        relay("PATCH", "/endpoints/" + str(endpoint) + "/disable")


if __name__ == "__main__":
    main()
