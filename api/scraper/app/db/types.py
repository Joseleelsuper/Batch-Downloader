import uuid
from typing import Any

from sqlalchemy.types import BINARY, CHAR, TypeDecorator


class GUID(TypeDecorator):
    """Store UUID as BINARY(16) on MySQL and CHAR(36) elsewhere."""

    impl = CHAR
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "mysql":
            return dialect.type_descriptor(BINARY(16))
        return dialect.type_descriptor(CHAR(36))

    def process_bind_param(self, value: Any, dialect):
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

    def process_result_value(self, value: Any, dialect):
        if value is None:
            return None
        if isinstance(value, uuid.UUID):
            return value
        if dialect.name == "mysql":
            return uuid.UUID(bytes=bytes(value))
        return uuid.UUID(str(value))


def uuid_pk() -> uuid.UUID:
    return uuid.uuid4()
