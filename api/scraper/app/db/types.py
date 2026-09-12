"""Adapta UUID Python a BINARY(16) en MySQL y a texto de 36 caracteres en otros dialectos."""
import uuid
from typing import Any

from sqlalchemy.types import BINARY, CHAR, TypeDecorator


class GUID(TypeDecorator):
    """Mantiene identidades UUID iguales entre la base MySQL y los dialectos de pruebas,
    aceptando representación binaria, textual o UUID.

    See Also:
        uuid_pk: Genera los identificadores usados como valor predeterminado.
    """

    impl = CHAR

    cache_ok = True


    def load_dialect_impl(self, dialect: Any) -> Any:
        """Selecciona el almacenamiento compacto de MySQL o su equivalente textual para otros
        motores.

        Args:
            dialect: Dialecto SQLAlchemy que determina la representación binaria o textual del
                UUID.

        Returns:
            descriptor BINARY(16) o CHAR(36), respectivamente.
        """

        if dialect.name == "mysql":
            return dialect.type_descriptor(BINARY(16))
        return dialect.type_descriptor(CHAR(36))

    def process_bind_param(self, value: Any, dialect: Any) -> Any:
        """Normaliza el UUID de entrada y obtiene bytes o texto según el dialecto; conserva None
        para valores SQL nulos.

        Args:
            value: Valor que se convierte al formato del destino.
            dialect: Dialecto SQLAlchemy que determina la representación binaria o textual del
                UUID.

        Returns:
            16 bytes en MySQL, cadena UUID en otros motores o None.

        Raises:
            ValueError: Si el valor no representa un UUID válido.
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
        """Recupera un UUID desde la representación de la base y conserva valores ya convertidos
        o nulos.

        Args:
            value: Valor que se convierte al formato del destino.
            dialect: Dialecto SQLAlchemy que determina la representación binaria o textual del
                UUID.

        Returns:
            UUID Python o None.
        """
        if value is None:
            return None
        if isinstance(value, uuid.UUID):
            return value
        if dialect.name == "mysql":
            return uuid.UUID(bytes=bytes(value))
        return uuid.UUID(str(value))


def uuid_pk() -> uuid.UUID:
    """Genera una identidad aleatoria para una nueva fila antes de insertarla.

    Returns:
        UUID de versión cuatro.
    """

    return uuid.uuid4()
