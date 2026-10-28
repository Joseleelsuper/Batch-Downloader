"""Inicializa la persistencia y la compatibilidad del driver MySQL asíncrono."""

from aiomysql import connection
from pymysql.converters import escape_bytes

# PyMySQL 1.2.3 conserva este nombre como texto; aiomysql 0.3.2 aún lo invoca.
# Retirar cuando se publique https://github.com/aio-libs/aiomysql/pull/1081.
if not callable(connection.escape_bytes_prefixed):
    connection.escape_bytes_prefixed = escape_bytes
