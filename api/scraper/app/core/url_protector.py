import base64
import hashlib

from cryptography.fernet import Fernet, InvalidToken


class UrlProtector:
    """Clase para proteger y revelar URLs utilizando cifrado simétrico con Fernet."""

    def __init__(self, secret: str) -> None:
        """Inicializa la clase UrlProtector con un secreto para cifrado.

        Args:
            secret (str): Secretillo
        """
        digest = hashlib.sha256(secret.encode("utf-8")).digest()
        self._fernet = Fernet(base64.urlsafe_b64encode(digest))

    def protect(self, value: str) -> str:
        """Cifra el valor

        Args:
            value (str): El valor a cifrar.

        Returns:
            str: El valor cifrado.
        """
        return self._fernet.encrypt(value.encode("utf-8")).decode("utf-8")

    def reveal(self, value: str) -> str | None:
        """Revela el valor cifrado.

        Args:
            value (str): El valor cifrado.

        Returns:
            Optional[str]: El valor descifrado o None si el descifrado falla.
        """
        try:
            return self._fernet.decrypt(value.encode("utf-8")).decode("utf-8")
        except InvalidToken:
            return None
