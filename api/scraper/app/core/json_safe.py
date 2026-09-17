"""Convierte valores de persistencia y diagnóstico en estructuras aptas para serialización JSON."""
from __future__ import annotations

import uuid
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Any


def json_safe(value: Any) -> Any:
    """Normaliza recursivamente mapas y colecciones, representa bytes como hexadecimal y
    convierte fechas, UUID y decimales en texto.
    Conserva valores primitivos, usa el valor de las enumeraciones y convierte otros objetos
    con str.

    Args:
        value: Valor que se convierte al formato del destino.

    Returns:
        valor JSON compatible; las claves de mapas son cadenas y las colecciones se convierten
            en listas.
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
