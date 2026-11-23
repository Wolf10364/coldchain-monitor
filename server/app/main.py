"""ColdChain Monitor dashboard server.

The phone uploads readings and its own verdict; this server stores them and serves the dashboard.
The shipment rules live only in the Kotlin core, so the server never second-guesses the verdict.

Run (from the server/ folder):  uvicorn app.main:create_app --factory --port 8000
Then open http://localhost:8000. Set DATABASE_URL to use another database than the local SQLite file.
"""
import json
import os
from collections.abc import Callable, Iterator
from datetime import datetime, timedelta, timezone
from pathlib import Path as FilePath
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Path, Query
from fastapi.responses import FileResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session, sessionmaker

from .db import ReadingRow, Shipment, make_engine
from .schemas import UploadIn

SHIPMENT_ID = r"^[A-Za-z0-9_-]{1,64}$"
PHONE_OFFLINE_AFTER = timedelta(seconds=15)  # the app uploads every 2 seconds
STATIC = FilePath(__file__).parent / "static"


def utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def to_naive_utc(t: datetime) -> datetime:
    return t.astimezone(timezone.utc).replace(tzinfo=None) if t.tzinfo else t


def iso(t: datetime) -> str:
    return t.replace(microsecond=0).isoformat() + "Z"


def create_app(database_url: str | None = None, clock: Callable[[], datetime] = utc_now) -> FastAPI:
    engine = make_engine(database_url or os.environ.get("DATABASE_URL", "sqlite:///coldchain.db"))
    sessions = sessionmaker(engine, expire_on_commit=False)
    app = FastAPI(title="ColdChain Monitor")

    def session() -> Iterator[Session]:
        with sessions() as s:
            yield s

    Db = Annotated[Session, Depends(session)]
    ShipmentId = Annotated[str, Path(pattern=SHIPMENT_ID)]

    @app.post("/api/shipments/{shipment_id}/upload")
    def upload(shipment_id: ShipmentId, body: UploadIn, s: Db) -> dict:
        now = clock()
        ship = s.get(Shipment, shipment_id)
        if ship is None:
            ship = Shipment(id=shipment_id, created_at=now)
            s.add(ship)
        ship.device = body.device
        ship.range_min_c = body.range_min_c
        ship.range_max_c = body.range_max_c
        ship.connection = body.connection
        ship.last_seen = now
        if body.report is not None:
            ship.report_json = body.report.model_dump_json()

        # The phone retries failed uploads, so the same reading can arrive twice: store it once.
        times = [to_naive_utc(r.time) for r in body.readings]
        known = set(s.scalars(select(ReadingRow.time).where(
            ReadingRow.shipment_id == shipment_id, ReadingRow.time.in_(times)))) if times else set()
        stored = 0
        for t, r in zip(times, body.readings):
            if t in known:
                continue
            known.add(t)
            s.add(ReadingRow(shipment_id=shipment_id, time=t, celsius=r.celsius))
            stored += 1
        s.commit()
        return {"stored": stored, "duplicates": len(times) - stored}

    def summary(s: Session, ship: Shipment) -> dict:
        latest = s.scalars(select(ReadingRow).where(ReadingRow.shipment_id == ship.id)
                           .order_by(ReadingRow.time.desc()).limit(1)).first()
        count = s.scalar(select(func.count()).select_from(ReadingRow).where(ReadingRow.shipment_id == ship.id))
        report = json.loads(ship.report_json) if ship.report_json else None
        since = clock() - ship.last_seen
        return {
            "id": ship.id,
            "device": ship.device,
            "range_min_c": ship.range_min_c,
            "range_max_c": ship.range_max_c,
            "connection": ship.connection,
            "phone_online": since <= PHONE_OFFLINE_AFTER,
            "seconds_since_seen": int(since.total_seconds()),
            "last_seen": iso(ship.last_seen),
            "created_at": iso(ship.created_at),
            "reading_count": count,
            "latest": {"time": iso(latest.time), "celsius": latest.celsius} if latest else None,
            "verdict": report["verdict"] if report else None,
            "report": report,
        }

    @app.get("/api/shipments")
    def list_shipments(s: Db) -> list[dict]:
        ships = s.scalars(select(Shipment).order_by(Shipment.last_seen.desc())).all()
        return [summary(s, ship) for ship in ships]

    @app.get("/api/shipments/{shipment_id}")
    def shipment_detail(shipment_id: ShipmentId, s: Db,
                        limit: Annotated[int, Query(ge=1, le=5000)] = 240) -> dict:
        ship = s.get(Shipment, shipment_id)
        if ship is None:
            raise HTTPException(404, "Unknown shipment")
        rows = s.scalars(select(ReadingRow).where(ReadingRow.shipment_id == shipment_id)
                         .order_by(ReadingRow.time.desc()).limit(limit)).all()
        return summary(s, ship) | {"readings": [{"time": iso(r.time), "celsius": r.celsius} for r in reversed(rows)]}

    @app.get("/", include_in_schema=False)
    def dashboard() -> FileResponse:
        return FileResponse(STATIC / "index.html")

    return app
