"""The upload the app sends. Field names match the Kotlin DTOs in core/.../DashboardSync.kt."""
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, Field

Connection = Literal["SCANNING", "CONNECTED", "LOST", "RECONNECTING"]


class ReadingIn(BaseModel):
    time: datetime
    celsius: float = Field(ge=-100, le=100)


class ExcursionIn(BaseModel):
    direction: Literal["TOO_WARM", "TOO_COLD"]
    start: datetime
    end: datetime
    peak_c: float


class GapIn(BaseModel):
    start: datetime
    end: datetime


class ReportIn(BaseModel):
    verdict: Literal["PASS", "FAIL", "INCOMPLETE"]
    reading_count: int = Field(ge=0)
    min_c: float
    max_c: float
    mean_kinetic_c: float
    time_out_of_range_s: int = Field(ge=0)
    alarms: int = Field(ge=0)
    excursions: list[ExcursionIn] = Field(default_factory=list)
    gaps: list[GapIn] = Field(default_factory=list)


Reason = Annotated[str, Field(max_length=200)]


class ReceiptIn(BaseModel):
    decision: Literal["ACCEPT", "QUARANTINE", "REJECT"]
    at: datetime
    arrival_c: float | None = None
    transport_verdict: Literal["PASS", "FAIL", "INCOMPLETE"] | None = None
    reasons: list[Reason] = Field(default_factory=list, max_length=20)


class PickingIn(BaseModel):
    start: datetime
    end: datetime | None = None
    out_of_cold_s: int = Field(ge=0)
    limit_s: int = Field(ge=0)
    max_c: float | None = None
    status: Literal["WITHIN_LIMITS", "PASSED", "FAILED"]
    reasons: list[Reason] = Field(default_factory=list, max_length=20)


class HandlingIn(BaseModel):
    """The warehouse checks: goods receipt at the dock, then picking. Decided on the phone, like the verdict."""
    stage: Literal["IN_TRANSIT", "RECEIVED", "PICKING", "PACKED"]
    receipt: ReceiptIn | None = None
    picking: PickingIn | None = None


class UploadIn(BaseModel):
    device: str = Field(min_length=1, max_length=120)
    range_min_c: float | None
    range_max_c: float
    connection: Connection
    readings: list[ReadingIn] = Field(default_factory=list, max_length=2000)
    report: ReportIn | None = None
    handling: HandlingIn | None = None
