from __future__ import annotations

import uuid
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any


def json_safe(value: Any) -> Any:
    """Convierte recursivamente un objeto a un tipo de dato seguro para JSON.

    Args:
        value (Any): El valor que se desea convertir a un tipo de dato seguro para JSON.

    Returns:
        Any: El valor convertido a un tipo de dato seguro para JSON.
    """
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, (bytearray, memoryview)):
        return bytes(value).hex()
    if isinstance(value, (datetime, date, uuid.UUID, Decimal)):
        return str(value)
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [json_safe(item) for item in value]
    return str(value)
