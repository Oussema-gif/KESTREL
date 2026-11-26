"""Verify a live telemetry-to-machine lookup and the REST/SOAP reservation lifecycle."""
from getpass import getpass
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request
from uuid import UUID, uuid4
from xml.etree import ElementTree as ET

from kestrel_local import OPENER

REST = "http://127.0.0.1:8081/api/v1"
SOAP = "http://127.0.0.1:8082/services/inventory"
SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
INVENTORY_NS = "urn:kestrel:inventory:v1"
BEARING = "00000000-0000-4000-8000-000000000101"


def require(condition, description):
    if not condition:
        raise RuntimeError(description)


def send(method, url, step, token=None, content_type=None, payload=None, **headers):
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if content_type:
        headers["Content-Type"] = content_type
    request = Request(url, data=payload, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=8) as response:
            return response.read()
    except HTTPError as error:
        with error:
            raise RuntimeError(f"{step} returned HTTP {error.code}.") from None
    except (URLError, TimeoutError) as error:
        raise RuntimeError(f"{step} did not return a response.") from error


def rest(method, route, token=None, data=None):
    body = json.dumps(data).encode() if data is not None else None
    response = send(method, REST + route, f"REST {method} {route}", token,
                    "application/json" if body is not None else None, body)
    return json.loads(response) if response else None


def soap(operation, token, **fields):
    envelope = ET.Element(f"{{{SOAP_NS}}}Envelope")
    body = ET.SubElement(envelope, f"{{{SOAP_NS}}}Body")
    request = ET.SubElement(body, f"{{{INVENTORY_NS}}}{operation}Request")
    for name, value in fields.items():
        ET.SubElement(request, f"{{{INVENTORY_NS}}}{name}").text = str(value)
    raw = send("POST", SOAP, f"SOAP {operation}", token, "text/xml; charset=UTF-8",
               ET.tostring(envelope, encoding="utf-8"),
               SOAPAction=f'"{INVENTORY_NS}/{operation}"')
    payload = ET.fromstring(raw).find(f"./{{{SOAP_NS}}}Body/{{{INVENTORY_NS}}}{operation}Response")
    require(payload is not None, f"SOAP {operation} response did not match the contract.")
    return {field.tag.split("}", 1)[-1]: field.text for field in payload}


def snapshot(root, machine_code, token):
    grpcurl = shutil.which("grpcurl")
    require(grpcurl is not None, "Install grpcurl and add it to PATH for the live test.")
    env = dict(os.environ, KESTREL_PHASE9_TOKEN=token)
    result = subprocess.run(
        [grpcurl, "-plaintext", "-max-time", "5", "-expand-headers",
         "-H", "authorization: Bearer ${KESTREL_PHASE9_TOKEN}",
         "-import-path", "contracts/grpc", "-proto", "telemetry.proto",
         "-d", "@", "127.0.0.1:9090", "kestrel.telemetry.TelemetryService/GetMachineSnapshot"],
        cwd=root, env=env, input=json.dumps({"machineCode": machine_code}),
        capture_output=True, text=True, timeout=8,
    )
    require(result.returncode == 0, "gRPC snapshot failed. Check Telemetry and the machine code.")
    reading = json.loads(result.stdout)
    age = int(time.time() * 1000) - int(reading.get("timestampEpochMs", 0))
    require(reading.get("machineCode") == machine_code and reading.get("machineStatus") in {"RUNNING", "WARNING", "CRITICAL", "MAINTENANCE"}
            and -2_000 <= age < 5_000,
            f"Expected a fresh gRPC snapshot for {machine_code}. Set the simulator to NORMAL.")
    for metric in ("temperatureC", "vibrationMmS", "pressureBar", "energyKw"):
        # Protobuf JSON omits numeric fields with the default value zero.
        require(math.isfinite(float(reading.get(metric, 0))), f"gRPC returned invalid {metric}.")


def available(token):
    return int(soap("getSparePart", token, partId=BEARING)["availableQuantity"])


def set_status(token, route, status):
    order = rest("PATCH", route + "/status", token, {"status": status})
    require(order["status"] == status, f"Expected work order status {status}.")


def record_reservation(token, route, reservation_id, status):
    order = rest("PATCH", route + "/reservation", token,
                 {"reservationId": reservation_id, "status": status})
    require(order["reservationId"] == reservation_id and order["reservationStatus"] == status,
            f"REST did not record reservation {reservation_id} as {status}.")
    return order


def release(token, reservation_id):
    reply = soap("releaseReservation", token,
                 reservationId=reservation_id, reason="Phase 9 test cleanup")
    require(reply["status"] == "RELEASED" and reply["reservationId"] == reservation_id,
            "SOAP did not confirm release of the test reservation.")


def reservation_flow(token, machine_id, machine_code, baseline):
    work_order = rest("POST", "/work-orders", token, {
        "machineId": machine_id,
        "machineCode": machine_code,
        "title": f"Phase 9 smoke {uuid4()}",
        "description": "Automated REST/SOAP reservation verification",
        "priority": "HIGH",
        "partRequirement": {"partId": BEARING, "quantity": 1},
    })
    order_id = str(UUID(work_order["id"]))
    route = f"/work-orders/{order_id}"
    request_id = str(uuid4())
    reserve_fields = dict(partId=BEARING, quantity=1, workOrderId=order_id, requestId=request_id)
    reservation_id = None
    attempted_reservation = False
    finished = False
    print(f"Test work order {order_id}, reservation request {request_id}.", flush=True)

    try:
        require(work_order["status"] == "OPEN", "Expected the new work order to be OPEN.")
        set_status(token, route, "CHECKING_INVENTORY")
        stock = soap("checkStock", token, partId=BEARING, requestedQuantity=1)
        require(stock["available"] in ("true", "1"), "Expected a bearing to be available.")

        attempted_reservation = True
        first = soap("reservePart", token, **reserve_fields)
        reservation_id = str(UUID(first["reservationId"]))
        require(first["status"] == "RESERVED" and int(first["reservedQuantity"]) == 1,
                "Expected SOAP to confirm a one-unit reservation.")
        replay = soap("reservePart", token, **reserve_fields)
        require(replay["reservationId"] == reservation_id and replay["status"] == "RESERVED",
                "Reserve replay did not return the original active reservation.")
        require(available(token) == baseline - 1, "Expected exactly one unit deducted.")

        for _ in range(2):
            linked = record_reservation(token, route, reservation_id, "RESERVED")
            require(linked["status"] == "READY_FOR_MAINTENANCE",
                    "Linking did not make the order READY_FOR_MAINTENANCE.")
        require(rest("GET", route, token)["reservationId"] == reservation_id,
                "GET did not return the persisted reservation UUID.")

        release(token, reservation_id)
        release(token, reservation_id)
        replay_after_release = soap("reservePart", token, **reserve_fields)
        require(replay_after_release["reservationId"] == reservation_id
                and replay_after_release["status"] == "RELEASED",
                "Reserve replay did not preserve the released reservation.")
        require(available(token) == baseline, "Expected exactly one unit restored.")
        record_reservation(token, route, reservation_id, "RELEASED")
        set_status(token, route, "CANCELLED")
        finished = True
        print(f"PASS. Reservation {reservation_id} linked and released. Stock restored.")
    finally:
        if not finished:
            try:
                if attempted_reservation and reservation_id is None:
                    # Resolve an uncertain SOAP result with the original request ID.
                    result = soap("reservePart", token, **reserve_fields)
                    reservation_id = str(UUID(result["reservationId"]))
                if reservation_id:
                    release(token, reservation_id)
                    current = rest("GET", route, token)
                    if current.get("reservationId") == reservation_id:
                        record_reservation(token, route, reservation_id, "RELEASED")
                set_status(token, route, "CANCELLED")
                print(f"Cleaned up test work order {order_id}.", file=sys.stderr)
            except (KeyError, ValueError, OSError, RuntimeError, ET.ParseError) as cleanup_error:
                print(f"Cleanup incomplete for work order {order_id}, request {request_id}, "
                      f"reservation {reservation_id or 'unconfirmed'}. "
                      f"{cleanup_error}", file=sys.stderr)


def verify(root, machine_code):
    require(re.fullmatch(r"[A-Z0-9]+(?:-[A-Z0-9]+)*", machine_code) and len(machine_code) <= 32,
            "Use a registered Kestrel machine code such as MACHINE-001.")
    password = getpass("Kestrel admin password: ")
    login = rest("POST", "/auth/login", data={"username": "admin", "password": password})
    del password
    token = login["accessToken"]
    try:
        require(rest("GET", "/auth/me", token)["role"] == "ADMIN", "Expected an ADMIN session.")
        snapshot(root, machine_code, token)
        query = urlencode({"machineCode": machine_code, "page": 0, "size": 10})
        machines = rest("GET", "/machines?" + query, token)["content"]
        matching = [machine for machine in machines if machine["machineCode"] == machine_code]
        require(len(matching) == 1, f"Register {machine_code} in Maintenance first.")
        print(f"PASS. Fresh gRPC snapshot and matching REST machine for {machine_code}.")
        baseline = available(token)
        require(baseline >= 1, "The demo bearing needs at least one available unit.")
        reservation_flow(token, str(UUID(matching[0]["id"])), machine_code, baseline)
    except (KeyError, ET.ParseError, subprocess.TimeoutExpired):
        raise RuntimeError("A service response did not match the live test contract or timed out.") from None
    finally:
        try:
            rest("POST", "/auth/logout", data={"refreshToken": login["refreshToken"]})
        except (RuntimeError, ValueError, KeyError):
            print("Could not revoke the test refresh token.", file=sys.stderr)
    print("PASS. Live REST, SOAP and gRPC verification.")
