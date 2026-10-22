import json
import os
import pathlib
import time
import urllib.error
import urllib.request


BASE = "http://relayforge:8080/api/v1/endpoints"
DIRECTORY = pathlib.Path("/run/webhook-secrets")
HEADERS = {"X-API-Key": os.environ["RELAYFORGE_API_KEY"], "Content-Type": "application/json"}
EVENTS = ["ticket.reserved", "ticket.purchased", "reservation.expired", "payment.failed", "concert.sold_out"]


def request(method, suffix="", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    query = urllib.request.Request(BASE + suffix, data=data, headers=HEADERS, method=method)
    with urllib.request.urlopen(query, timeout=5) as response:
        return json.load(response)


for attempt in range(30):
    try:
        endpoints = request("GET")
        break
    except (urllib.error.URLError, TimeoutError):
        if attempt == 29:
            raise SystemExit("RelayForge did not become ready") from None
        time.sleep(2)

DIRECTORY.mkdir(parents=True, exist_ok=True)
for recipient in ("tickets", "accounting", "notifications"):
    url = "http://eventrush:8082/webhooks/" + recipient
    matches = [endpoint for endpoint in endpoints if endpoint["url"] == url]
    if len(matches) > 1:
        raise SystemExit("Duplicate demo receiver endpoints require cleanup")
    subscriptions = EVENTS if recipient == "notifications" else ["ticket.purchased"]
    endpoint = matches[0] if matches else request("POST", body={"url": url, "subscribedEvents": subscriptions})
    identifier = str(endpoint["id"])
    request("PUT", "/" + identifier + "/subscriptions", {"subscribedEvents": subscriptions})
    secret_file = DIRECTORY / ("eventrush.downstream." + recipient + "-secret")
    binding_file = DIRECTORY / (recipient + ".endpoint-id")
    if not secret_file.exists() or not binding_file.exists() or binding_file.read_text() != identifier:
        secret = request("POST", "/" + identifier + "/rotate-secret")["secret"]
        temporary = secret_file.with_suffix(".tmp")
        temporary.write_text(secret, encoding="utf-8")
        temporary.chmod(0o444)
        temporary.replace(secret_file)
        binding_file.write_text(identifier, encoding="utf-8")
    if not endpoint["enabled"]:
        request("PATCH", "/" + identifier + "/enable")
print("Three webhook subscriptions configured")
