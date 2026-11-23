from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

T0 = datetime(2026, 11, 23, 8, 0, 0)


class Clock:
    def __init__(self):
        self.now = T0

    def __call__(self):
        return self.now


@pytest.fixture
def clock():
    return Clock()


@pytest.fixture
def client(tmp_path, clock):
    return TestClient(create_app(f"sqlite:///{tmp_path / 'test.db'}", clock=clock))


def upload(minutes, celsius=4.0, verdict="PASS", connection="CONNECTED", range_min_c=2.0):
    return {
        "device": "Pixel 9 Pro XL",
        "range_min_c": range_min_c,
        "range_max_c": 8.0,
        "connection": connection,
        "readings": [{"time": f"2026-11-23T08:{m:02d}:00Z", "celsius": celsius} for m in minutes],
        "report": {
            "verdict": verdict, "reading_count": len(minutes), "min_c": celsius, "max_c": celsius,
            "mean_kinetic_c": celsius, "time_out_of_range_s": 0, "alarms": 0, "excursions": [], "gaps": [],
        },
    }


def test_upload_creates_the_shipment(client):
    r = client.post("/api/shipments/SHP-1/upload", json=upload([0, 1, 2], celsius=4.2))
    assert r.json() == {"stored": 3, "duplicates": 0}

    ships = client.get("/api/shipments").json()
    assert len(ships) == 1
    ship = ships[0]
    assert ship["id"] == "SHP-1"
    assert ship["verdict"] == "PASS"
    assert ship["reading_count"] == 3
    assert ship["latest"] == {"time": "2026-11-23T08:02:00Z", "celsius": 4.2}
    assert ship["phone_online"] is True


def test_a_retried_upload_stores_each_reading_once(client):
    client.post("/api/shipments/SHP-1/upload", json=upload([0, 1, 2]))
    r = client.post("/api/shipments/SHP-1/upload", json=upload([1, 2, 3]))
    assert r.json() == {"stored": 1, "duplicates": 2}
    assert client.get("/api/shipments").json()[0]["reading_count"] == 4


def test_latest_verdict_and_connection_win(client):
    client.post("/api/shipments/SHP-1/upload", json=upload([0]))
    client.post("/api/shipments/SHP-1/upload", json=upload([], verdict="FAIL", connection="LOST"))
    ship = client.get("/api/shipments").json()[0]
    assert ship["verdict"] == "FAIL"
    assert ship["connection"] == "LOST"


def test_detail_returns_readings_in_time_order(client):
    client.post("/api/shipments/SHP-1/upload", json=upload([5, 1, 3]))
    detail = client.get("/api/shipments/SHP-1").json()
    assert [r["time"][14:16] for r in detail["readings"]] == ["01", "03", "05"]
    assert detail["report"]["verdict"] == "PASS"


def test_detail_limit_keeps_the_newest(client):
    client.post("/api/shipments/SHP-1/upload", json=upload(list(range(10))))
    detail = client.get("/api/shipments/SHP-1?limit=3").json()
    assert [r["time"][14:16] for r in detail["readings"]] == ["07", "08", "09"]


def test_phone_goes_offline_when_uploads_stop(client, clock):
    client.post("/api/shipments/SHP-1/upload", json=upload([0]))
    clock.now = T0 + timedelta(seconds=60)
    ship = client.get("/api/shipments").json()[0]
    assert ship["phone_online"] is False
    assert ship["seconds_since_seen"] == 60


def test_most_recently_active_shipment_comes_first(client, clock):
    client.post("/api/shipments/SHP-A/upload", json=upload([0]))
    clock.now = T0 + timedelta(seconds=5)
    client.post("/api/shipments/SHP-B/upload", json=upload([0]))
    assert [s["id"] for s in client.get("/api/shipments").json()] == ["SHP-B", "SHP-A"]


def test_frozen_range_has_no_lower_limit(client):
    assert client.post("/api/shipments/SHP-1/upload", json=upload([0], range_min_c=None)).status_code == 200
    assert client.get("/api/shipments").json()[0]["range_min_c"] is None


def test_bad_input_is_rejected(client):
    assert client.post("/api/shipments/bad id!/upload", json=upload([0])).status_code in (404, 422)
    assert client.post("/api/shipments/SHP-1/upload", json=upload([0]) | {"connection": "ON_FIRE"}).status_code == 422
    assert client.post("/api/shipments/SHP-1/upload", json=upload([0], celsius=500.0)).status_code == 422


def test_unknown_shipment_is_404(client):
    assert client.get("/api/shipments/NOPE").status_code == 404


def test_dashboard_page_is_served(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "ColdChain" in r.text
