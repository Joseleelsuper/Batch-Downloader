"""Implementa las responsabilidades del módulo `url_protector`.
"""
import base64
import hashlib

from cryptography.fernet import Fernet, InvalidToken


class UrlProtector:
    """Representa el componente `UrlProtector`.
    """

    def __init__(self, secret: str) -> None:
        """Inicializa una instancia de `UrlProtector`.

        Args:
            secret (str): Valor de `secret` utilizado por la operación.
        """
        digest = hashlib.sha256(secret.encode("utf-8")).digest()
        self._fernet = Fernet(base64.urlsafe_b64encode(digest))
        """Estado de instancia asociado a `_fernet`.
        """

    def protect(self, value: str) -> str:
        """Ejecuta `protect` dentro de `UrlProtector`.

        Args:
            value (str): Valor que debe procesarse.

        Returns:
            str: Resultado producido por la operación.
        """
        return self._fernet.encrypt(value.encode("utf-8")).decode("utf-8")

    def reveal(self, value: str) -> str | None:
        """Ejecuta `reveal` dentro de `UrlProtector`.

        Args:
            value (str): Valor que debe procesarse.

        Returns:
            str | None: Resultado producido por la operación.
        """
        try:
            return self._fernet.decrypt(value.encode("utf-8")).decode("utf-8")
        except InvalidToken:
            return None
