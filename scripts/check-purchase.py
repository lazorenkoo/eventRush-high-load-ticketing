"""Run one real purchase against the Compose stack; creates a separate one-seat concert."""
import datetime
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request


BASE = os.environ.get("EVENTRUSH_CHECK_URL", "http://gateway")
GATEWAY = os.environ.get("CROWDGATE_CHECK_URL", "http://crowdgate:8080")
ORIGIN = os.environ.get("EVENTRUSH_CHECK_ORIGIN", "http://localhost:8082")
cookies = http.cookiejar.CookieJar()
browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(method, path, body=None, admin=False, crowdgate=False):
    headers = {"Origin": ORIGIN, "Accept": "application/json"}
    if admin:
        headers["X-Admin-Key"] = os.environ["CG_ADMIN_KEY" if crowdgate else "EVENTRUSH_ADMIN_API_KEY"]
    data = None if body is None else json.dumps(body).encode("utf-8")
    if data is not None:
        headers["Content-Type"] = "application/json"
    target = (GATEWAY if crowdgate else BASE) + path
    query = urllib.request.Request(target, data=data, headers=headers, method=method)
    with browser.open(query, timeout=5) as response:
        raw = response.read(65537)
        assert len(raw) <= 65536, "Response exceeds bound"
        return json.loads(raw) if raw else None


def eventually(check, label, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(1)
    raise AssertionError(label + " timed out")


def main():
    now = datetime.datetime.now(datetime.timezone.utc)
    gate = request("POST", "/api/v1/admin/gates", {
        "name": "Purchase verification", "admissionRate": 1, "maxActiveUsers": 2,
        "sessionTtlSeconds": 300, "tokenTtlSeconds": 60}, admin=True, crowdgate=True)["id"]
    concert = request("POST", "/api/v1/admin/concerts", {
        "name": "Purchase verification", "startsAt": (now + datetime.timedelta(days=1)).isoformat(),
        "saleStartsAt": (now - datetime.timedelta(minutes=1)).isoformat(), "crowdgateGateId": gate}, admin=True)["id"]
    admin = "/api/v1/admin/concerts/" + concert
    seat = request("POST", admin + "/seats", {
        "seats": [{"section": "CHECK", "row": "1", "number": 1, "price": 25}]}, admin=True)[0]
    request("POST", admin + "/open", admin=True)
    request("POST", "/api/v1/admin/gates/" + gate + "/open", admin=True, crowdgate=True)
    entry = request("POST", "/api/v1/gates/" + gate + "/join")
    token = entry["queueToken"]
    assert token.startswith(gate + ":"), "Queue gate mismatch"
    participant = request("POST", "/api/v1/participants", {
        "concertId": concert, "seatId": seat, "queueToken": token,
        "firstName": "Purchase", "lastName": "Check"})["id"]

    def admitted():
        current = request("GET", "/api/v1/queue/" + token)
        assert current["queueToken"] == token, "Queue token changed"
        assert current["status"] in ("WAITING", "ADMITTED"), "Unexpected queue state"
        return current["status"] == "ADMITTED"

    eventually(admitted, "Admission")
    admission = request("POST", "/api/v1/queue/" + token + "/admission-token")["admissionToken"]
    request("POST", "/api/v1/admission/exchange", {"concertId": concert, "admissionToken": admission})
    reserved = request("POST", "/api/v1/seats/" + seat + "/reserve?participantId=" + participant)
    assert reserved["seatId"] == seat and reserved["status"] == "ACTIVE", "Invalid reservation"
    reservation = reserved["id"]
    path = "/api/v1/reservations/" + reservation
    payment = request("POST", path + "/pay", {"mode": "SUCCESS"})
    assert payment["reservationId"] == reservation, "Payment owner mismatch"
    eventually(lambda: request("GET", path)["status"] == "CONFIRMED", "Payment confirmation")

    def downstream():
        tickets = request("GET", "/api/v1/me/tickets")
        notifications = request("GET", "/api/v1/me/notifications")
        journal = request("GET", admin + "/journal?q=" + participant, admin=True)["items"]
        kinds = {item["kind"] for item in journal}
        return (any(item["reservationId"] == reservation and item["ticketNumber"] > 0 for item in tickets)
                and any(item["eventType"] == "ticket.purchased" for item in notifications)
                and {"TICKET", "ACCOUNTING", "NOTIFICATION"}.issubset(kinds))

    eventually(downstream, "RelayForge downstream delivery", 120)
    stats = request("GET", admin + "/stats", admin=True)
    assert stats["sold"] == 1 and stats["successfulPayments"] == 1 and stats["revenue"] == 25, "Sales mismatch"
    request("POST", "/api/v1/admission/logout")
    print(json.dumps({"result": "PASS", "concertId": concert, "reservationId": reservation,
                      "verified": "queue, admission, purchase, ticket, accounting, notification"}))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit("Purchase check failed: HTTP " + str(error.code)) from None
