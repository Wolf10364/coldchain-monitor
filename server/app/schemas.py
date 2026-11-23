"""The upload the app sends. Field names match the Kotlin DTOs in core/.../DashboardSync.kt."""
from datetime import datetime
from typing import Literal

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


class UploadIn(BaseModel):
    device: str = Field(min_length=1, max_length=120)
    range_min_c: float | None
    range_max_c: float
    connection: Connection
    readings: list[ReadingIn] = Field(default_factory=list, max_length=2000)
    report: ReportIn | None = None
