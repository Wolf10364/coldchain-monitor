"""Database tables: one row per shipment, one row per reading.

SQLite by default so the project runs with no setup; any SQLAlchemy URL works (for example PostgreSQL).
"""
from datetime import datetime

from sqlalchemy import Float, ForeignKey, String, Text, UniqueConstraint, create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Shipment(Base):
    __tablename__ = "shipments"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    device: Mapped[str] = mapped_column(String(120))
    range_min_c: Mapped[float | None] = mapped_column(Float, nullable=True)  # None: no lower limit (frozen)
    range_max_c: Mapped[float] = mapped_column(Float)
    connection: Mapped[str] = mapped_column(String(20))
    report_json: Mapped[str | None] = mapped_column(Text, nullable=True)  # the verdict as the app computed it
    created_at: Mapped[datetime]
    last_seen: Mapped[datetime]  # last upload from the phone (UTC)


class ReadingRow(Base):
    __tablename__ = "readings"
    __table_args__ = (UniqueConstraint("shipment_id", "time"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    shipment_id: Mapped[str] = mapped_column(ForeignKey("shipments.id"), index=True)
    time: Mapped[datetime]  # UTC
    celsius: Mapped[float] = mapped_column(Float)


def make_engine(url: str) -> Engine:
    args = {"check_same_thread": False} if url.startswith("sqlite") else {}
    engine = create_engine(url, connect_args=args)
    Base.metadata.create_all(engine)
    return engine
