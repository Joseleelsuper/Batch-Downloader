"""Implementa las responsabilidades del módulo `types`.
"""
import uuid
from typing import Any

from sqlalchemy.types import BINARY, CHAR, TypeDecorator


class GUID(TypeDecorator):
    """Representa el componente `GUID`.
    """

    impl = CHAR
    """Atributo de clase `impl` de `GUID`.
    """
    cache_ok = True
    """Atributo de clase `cache_ok` de `GUID`.
    """

    def load_dialect_impl(self, dialect: Any) -> Any:
        """Carga la operación `dialect_impl`.

        Args:
            dialect (Any): Valor de `dialect` utilizado por la operación.

        Returns:
            Any: Resultado de `load_dialect_impl`.
        """

        if dialect.name == "mysql":
            return dialect.type_descriptor(BINARY(16))
        return dialect.type_descriptor(CHAR(36))

    def process_bind_param(self, value: Any, dialect: Any) -> Any:
        """Procesa la operación `bind_param`.

        Args:
            value (Any): Valor que debe procesarse.
            dialect (Any): Valor de `dialect` utilizado por la operación.

        Returns:
            Any: Resultado producido por la operación.
        """

        if value is None:
            return None
        if isinstance(value, (bytes, bytearray, memoryview)):
            raw = bytes(value)
            if len(raw) == 16:
                return raw if dialect.name == "mysql" else str(uuid.UUID(bytes=raw))
        if not isinstance(value, uuid.UUID):
            value = uuid.UUID(str(value))
        if dialect.name == "mysql":
            return value.bytes
        return str(value)

    def process_result_value(self, value: Any, dialect: Any) -> Any:
        """Procesa la operación `result_value`.

        Args:
            value (Any): Valor que debe procesarse.
            dialect (Any): Valor de `dialect` utilizado por la operación.

        Returns:
            Any: Resultado producido por la operación.
        """
        if value is None:
            return None
        if isinstance(value, uuid.UUID):
            return value
        if dialect.name == "mysql":
            return uuid.UUID(bytes=bytes(value))
        return uuid.UUID(str(value))


def uuid_pk() -> uuid.UUID:
    """Ejecuta la operación `uuid_pk`.

    Returns:
        uuid.UUID: Resultado producido por la operación.
    """

    return uuid.uuid4()
