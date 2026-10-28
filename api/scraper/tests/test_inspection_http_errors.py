"""La política HTTP compartida conserva los fallos aceptados por cada ruta de inspección."""
import pytest
from fastapi import HTTPException

from app.api.internal_routes import inspection_http_errors
from app.scraper.manual_installer import ManualInstallerError, ManualInstallerTransientError
from app.scraper.safe_http import SafeHttpError
from app.scraper.website_discovery import WebsiteDiscoveryError, WebsiteDiscoveryTransientError


@pytest.mark.parametrize("failure,status", [
    (ManualInstallerError("inspection_not_found", 404), 404),
    (ManualInstallerError("inspection_changed", 409), 409),
    (WebsiteDiscoveryError("invalid_input", 422), 422),
    (WebsiteDiscoveryTransientError("network_error"), 503),
    (ManualInstallerTransientError("network_error"), 503),
    (SafeHttpError("dns_not_public"), 422),
    (SafeHttpError("timeout", transient=True), 503),
])
def test_preserves_status_code_and_original_cause(failure, status):
    with pytest.raises(HTTPException) as raised:
        with inspection_http_errors(type(failure)):
            raise failure
    assert raised.value.status_code == status
    assert raised.value.detail == {"code": failure.code}
    assert raised.value.__cause__ is failure


def test_only_translates_the_error_types_selected_by_the_route():
    failure = ManualInstallerTransientError("unexpected_transient_error")
    with pytest.raises(ManualInstallerTransientError) as raised:
        with inspection_http_errors(ManualInstallerError, SafeHttpError):
            raise failure
    assert raised.value is failure
