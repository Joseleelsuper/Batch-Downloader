"""Protege las URL de instaladores persistidas para que su texto no forme parte del catálogo
público.
"""
import base64
import hashlib

from cryptography.fernet import Fernet, InvalidToken


class UrlProtector:
    """Cifra y autentica URL con Fernet utilizando una clave derivada del secreto del despliegue.
    La recuperación devuelve None cuando el token no puede autenticarse; no valida la
    seguridad de la URL recuperada.

    See Also:
        app.scraper.safe_http: Valida destinos antes del acceso HTTP.
        app.repositories.catalog_sources: Persiste las fuentes con sus URL protegidas.
    """

    def __init__(self, secret: str) -> None:
        """Deriva una clave Fernet de 32 bytes del secreto UTF-8 y prepara el cifrador para las
        fuentes del despliegue.

        Args:
            secret: Secreto del despliegue del que se deriva la clave Fernet mediante SHA-256.
        """
        digest = hashlib.sha256(secret.encode("utf-8")).digest()
        self._fernet = Fernet(base64.urlsafe_b64encode(digest))


    def protect(self, value: str) -> str:
        """Cifra el texto UTF-8 y devuelve un token autenticado que puede persistirse como
        cadena.

        Args:
            value: URL en texto claro que se protege antes de almacenarla.

        Returns:
            token Fernet en texto ASCII compatible con UTF-8.
        """
        return self._fernet.encrypt(value.encode("utf-8")).decode("utf-8")

    def reveal(self, value: str) -> str | None:
        """Descifra un token y recupera su texto sin comprobar esquema, DNS ni caducidad de la
        fuente.

        Args:
            value: Token Fernet persistido para la URL.

        Returns:
            URL original, o None cuando Fernet rechaza la autenticidad del token.
        """
        try:
            return self._fernet.decrypt(value.encode("utf-8")).decode("utf-8")
        except InvalidToken:
            return None
