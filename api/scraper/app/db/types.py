import uuid
from typing import Any

from sqlalchemy.types import BINARY, CHAR, TypeDecorator


class GUID(TypeDecorator):
    """GUID (Globally Unique Identifier) para SQLAlchemy."""

    impl = CHAR
    cache_ok = True

    def load_dialect_impl(self, dialect: Any) -> Any:
        """Carga la implementación del tipo de datos GUID según el dialecto de la base de datos.

        Args:
            dialect (Any): El dialecto de la base de datos.

        Returns:
            Any: La implementación del tipo de datos GUID para el dialecto especificado.
        """

        if dialect.name == "mysql":
            return dialect.type_descriptor(BINARY(16))
        return dialect.type_descriptor(CHAR(36))

    def process_bind_param(self, value: Any, dialect: Any) -> Any:
        """Procesa el valor del parámetro de enlace antes de enviarlo a la base de datos.

        Args:
            value (Any): El valor del parámetro de enlace.
            dialect (Any): El dialecto de la base de datos.

        Returns:
            Any: El valor procesado del parámetro de enlace para la base de datos.
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
        """Procesa el valor del resultado de la consulta después de recuperarlo de la base de datos.

        Args:
            value (Any): El valor del resultado de la consulta.
            dialect (Any): El dialecto de la base de datos.

        Returns:
            Any: El valor procesado del resultado de la consulta.
        """
        if value is None:
            return None
        if isinstance(value, uuid.UUID):
            return value
        if dialect.name == "mysql":
            return uuid.UUID(bytes=bytes(value))
        return uuid.UUID(str(value))


def uuid_pk() -> uuid.UUID:
    """Genera un UUID único para usar como clave primaria.

    Returns:
        uuid.UUID: Un UUID único para usar como clave primaria.
    """

    return uuid.uuid4()
