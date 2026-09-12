"""Comprueba rutas, autenticación y normalización del contexto después de separar los routers."""

from fastapi.testclient import TestClient

from app.admin_request_context import admin_request_context
from app.main import app


def test_router_composition_keeps_health_public_and_admin_private() -> None:
    """La sonda pública responde sin token y el catálogo privado rechaza la petición antes de
    consultar PostgreSQL.
    Comprueba además cabeceras administrativas y respuesta 202 en el esquema del benchmark.
    """
    client = TestClient(app)
    assert client.get("/semantic/health/live").json() == {
        "status": "ok", "service": "semantic-service",
    }
    response = client.get("/internal/v1/admin/semantic/models")
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "invalid_internal_token"
    operation = app.openapi()["paths"]["/internal/v1/admin/semantic/benchmarks"]["post"]
    headers = {parameter["name"] for parameter in operation["parameters"]}
    assert {"X-Admin-Actor", "Idempotency-Key", "X-Internal-Service-Token"} <= headers
    assert "202" in operation["responses"]


def test_admin_context_preserves_empty_values_trimming_and_storage_limits() -> None:
    """Actor vacío toma admin y clave vacía toma None; las entradas largas respetan límites de
    120 y 200 caracteres.
    """
    empty = admin_request_context("  ", "  ")
    assert empty.actor == "admin"
    assert empty.idempotency_key is None
    full = admin_request_context(" " + "a" * 130, " " + "b" * 210)
    assert full.actor == "a" * 120
    assert full.idempotency_key == "b" * 200
