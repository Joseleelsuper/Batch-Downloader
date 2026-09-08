"""Validación de recetas, CAS, ownership, grafo y resolución Linux."""

from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.db.models import SoftwareApp
from app.schemas.linux_install import LinuxInstallProfile
from tests.test_internal_routes import INTERNAL_TOKEN, InternalApiFixture
from tests.test_internal_routes import internal_api as internal_api

HEADERS = {"X-Internal-Service-Token": INTERNAL_TOKEN}


@pytest.mark.parametrize(
    "profile",
    [
        {"strategy": "deb", "command": "sudo anything"},
        {"strategy": "tarball", "entrypoint": "../outside"},
        {"strategy": "tarball", "entrypoint": "/outside"},
        {"strategy": "jar"},
        {"strategy": "deb", "systemPackages": {"apt": ["--help"]}},
        {
            "strategy": "deb",
            "update": {
                "provider": "official",
                "url": "http://host.test",
                "allowedHosts": ["host.test"],
            },
        },
    ],
)
def test_rejects_executable_or_unsafe_recipes(profile):
    with pytest.raises(ValidationError):
        LinuxInstallProfile.model_validate(profile)


async def test_profile_is_source_bound_versioned_and_only_approved_is_resolved(
    internal_api: InternalApiFixture,
):
    app, resolved, _ = await internal_api.add_source(confidence="validated")
    resolved.source.operating_system = "linux"
    resolved.extension = ".tar.gz"
    resolved.filename = "app.tar.gz"
    await internal_api.session.commit()
    path = f"/internal/v1/linux/apps/{app.id}/sources/{resolved.id}/profile"
    assert (await internal_api.client.get(path)).status_code == 401
    initial = await internal_api.client.get(path, headers=HEADERS)
    assert initial.json()["profile"]["strategy"] == "manual"
    body = {
        "expectedVersion": 0,
        "status": "draft",
        "profile": {"strategy": "tarball", "entrypoint": "app/bin/run"},
    }
    response = await internal_api.client.put(path, json=body, headers=HEADERS)
    assert response.status_code == 200, response.text
    assert response.json()["version"] == 1
    resolution = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers=HEADERS,
    )
    assert resolution.json()["installationProfile"]["strategy"] == "manual"
    assert (await internal_api.client.put(path, json=body, headers=HEADERS)).status_code == 409
    body.update(expectedVersion=1, status="approved")
    assert (await internal_api.client.put(path, json=body, headers=HEADERS)).status_code == 200
    resolution = await internal_api.client.get(
        f"/internal/v1/sources/{resolved.id}/resolution",
        headers=HEADERS,
    )
    assert resolution.json()["installationProfile"]["strategy"] == "tarball"
    assert resolution.json()["appName"] == app.name
    wrong_owner = path.replace(str(app.id), str(uuid4()))
    assert (await internal_api.client.get(wrong_owner, headers=HEADERS)).status_code == 404
    body["expectedVersion"] = 2
    body["profile"]["strategy"] = "rpm"
    assert (await internal_api.client.put(path, json=body, headers=HEADERS)).status_code == 422


async def test_dependency_graph_rejects_cycles_and_stale_versions(internal_api: InternalApiFixture):
    ids = [uuid4() for _ in range(3)]
    for identifier in ids:
        internal_api.session.add(
            SoftwareApp(
                id=identifier,
                winstall_id=str(identifier),
                slug=str(identifier),
                name=str(identifier),
                normalized_name=str(identifier),
                app_status="active",
            )
        )
    await internal_api.session.commit()

    async def save(index, dependencies, version=0):
        return await internal_api.client.put(
            f"/internal/v1/linux/apps/{ids[index]}/dependencies",
            headers=HEADERS,
            json={"expectedVersion": version, "dependencies": [str(d) for d in dependencies]},
        )

    assert (await save(0, [ids[1]])).status_code == 200
    assert (await save(1, [ids[2]])).status_code == 200
    assert (await save(2, [ids[0]])).status_code == 422
    assert (await save(0, [])).status_code == 409
    assert (await save(0, [], 1)).status_code == 200
    assert (await save(2, [uuid4()])).status_code == 422
