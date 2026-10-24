"""Create an isolated eleven-seat concert for explicit 1/10-client k6 smoke checks."""
import datetime
import importlib.util
import json
import pathlib


spec = importlib.util.spec_from_file_location("purchase", pathlib.Path(__file__).with_name("check-purchase.py"))
purchase = importlib.util.module_from_spec(spec)
spec.loader.exec_module(purchase)


def main():
    now = datetime.datetime.now(datetime.timezone.utc)
    gate = purchase.request("POST", "/api/v1/admin/gates", {
        "name": "k6 smoke", "admissionRate": 5, "maxActiveUsers": 20,
        "sessionTtlSeconds": 300, "tokenTtlSeconds": 60}, admin=True, crowdgate=True)["id"]
    concert = purchase.request("POST", "/api/v1/admin/concerts", {
        "name": "k6 smoke", "startsAt": (now + datetime.timedelta(days=1)).isoformat(),
        "saleStartsAt": (now - datetime.timedelta(minutes=1)).isoformat(), "crowdgateGateId": gate}, admin=True)["id"]
    admin = "/api/v1/admin/concerts/" + concert
    seats = purchase.request("POST", admin + "/seats", {"seats": [
        {"section": "SMOKE", "row": "1", "number": number, "price": 25}
        for number in range(1, 12)]}, admin=True)
    purchase.request("POST", admin + "/open", admin=True)
    purchase.request("POST", "/api/v1/admin/gates/" + gate + "/open", admin=True, crowdgate=True)
    assert len(seats) == 11
    print(json.dumps({"concertId": concert, "seatIds": seats, "gateId": gate,
                      "purpose": "explicit small k6 smoke; no traffic started by fixture setup"}))


if __name__ == "__main__":
    main()
