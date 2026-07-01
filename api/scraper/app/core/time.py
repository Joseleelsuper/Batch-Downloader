from datetime import UTC, datetime, timedelta


def utc_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def utc_after(**kwargs: int) -> datetime:
    return utc_now() + timedelta(**kwargs)
