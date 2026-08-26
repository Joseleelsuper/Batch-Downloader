"""Contiene las pruebas de `test_winstall_client`."""

from collections.abc import Iterator

import httpx
import pytest

from app.core.config import Settings
from app.scraper.winstall import (
    WinstallCatalogIncompleteError,
    WinstallCatalogUnstableError,
    WinstallClient,
    WinstallDetailIncompleteError,
    extract_next_data,
    extract_winstall_downloads,
    extract_winstall_page_links,
    parse_winstall_app,
)


def test_extract_next_data_app_payload() -> None:
    """Comprueba el escenario `extract_next_data_app_payload`.
    """
    html = """
    <html><body>
      <script id="__NEXT_DATA__" type="application/json">
      {"props":{"pageProps":{"app":{"_id":"Vendor.App","name":"Vendor App"}}}}
      </script>
    </body></html>
    """

    payload = extract_next_data(html, "app")

    assert payload == {"_id": "Vendor.App", "name": "Vendor App"}


def test_parse_winstall_app_versions() -> None:
    """Comprueba el escenario `parse_winstall_app_versions`.
    """
    app = parse_winstall_app(
        {
            "_id": "EpicGames.EpicGamesLauncher",
            "name": "Epic Games Launcher",
            "desc": "Game store",
            "publisher": "Epic Games, Inc.",
            "homepage": "https://epicgames.com/download",
            "latestVersion": "1.2.3",
            "tags": ["games"],
            "versions": [
                {
                    "version": "1.2.3",
                    "installerType": "msi",
                    "installers": ["https://cdn.example.com/EpicInstaller.msi"],
                }
            ],
        }
    )

    assert app.package_id == "EpicGames.EpicGamesLauncher"
    assert app.tags == ["games"]
    assert app.installer_urls == ["https://cdn.example.com/EpicInstaller.msi"]
    assert app.installer_data_complete is True


def test_parse_reduced_winstall_html_is_not_authoritative() -> None:
    """El HTML nuevo no puede interpretarse como una lista vacía de instaladores."""
    app = parse_winstall_app(
        {
            "_id": "Valve.Steam",
            "name": "Steam",
            "latestVersion": "2.10.91.91",
            "versions": [{"version": "2.10.91.91"}],
        }
    )

    assert app.installer_urls == []
    assert app.installer_data_complete is False


@pytest.mark.asyncio
async def test_get_app_uses_exact_search_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    """La búsqueda solo se acepta cuando devuelve el mismo ID y detalle completo."""
    client = WinstallClient(Settings(), httpx.AsyncClient())

    async def no_direct(_package_id: str) -> None:
        return None

    async def exact_search(_package_id: str) -> dict[str, object]:
        return {
            "_id": "Valve.Steam",
            "name": "Steam",
            "versions": [
                {
                    "version": "2.10.91.91",
                    "installers": ["https://cdn.example.test/SteamSetup.exe"],
                }
            ],
        }

    monkeypatch.setattr(client, "_fetch_app", no_direct)
    monkeypatch.setattr(client, "_fetch_app_from_search", exact_search)

    app = await client.get_app("Valve.Steam")

    assert app.installer_data_complete is True
    assert app.installer_urls == ["https://cdn.example.test/SteamSetup.exe"]
    await client._client.aclose()  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_get_app_rejects_reduced_html_as_negative_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Una caída del detalle no debe convertirse en un falso ``missing``."""
    client = WinstallClient(Settings(), httpx.AsyncClient())

    async def no_detail(_package_id: str) -> None:
        return None

    async def reduced_html(_package_id: str) -> dict[str, object]:
        return {
            "_id": "Valve.Steam",
            "name": "Steam",
            "versions": [{"version": "2.10.91.91"}],
        }

    monkeypatch.setattr(client, "_fetch_app", no_detail)
    monkeypatch.setattr(client, "_fetch_app_from_search", no_detail)
    monkeypatch.setattr(client, "_fetch_app_from_page", reduced_html)

    with pytest.raises(WinstallDetailIncompleteError, match="html_detail_is_slim"):
        await client.get_app("Valve.Steam")
    await client._client.aclose()  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_catalog_snapshot_requires_two_identical_passes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """La paginación mutable solo se publica tras dos conjuntos consecutivos iguales."""
    client = WinstallClient(Settings(), httpx.AsyncClient())
    passes: Iterator[list[dict[str, object]]] = iter(
        [
            [{"_id": "Vendor.First", "name": "First"}],
            [{"_id": "Vendor.Second", "name": "Second"}],
            [{"_id": "Vendor.Second", "name": "Second"}],
        ]
    )

    async def next_pass() -> list[dict[str, object]]:
        return next(passes)

    monkeypatch.setattr(client, "_fetch_complete_catalog_once", next_pass)

    snapshot = await client.catalog_snapshot(max_attempts=3)

    assert [app.package_id for app in snapshot] == ["Vendor.Second"]
    await client._client.aclose()  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_catalog_snapshot_fails_when_catalog_never_stabilizes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Una pasada incompleta no se declara terminada con éxito."""
    client = WinstallClient(Settings(), httpx.AsyncClient())
    counter = 0

    async def changing_pass() -> list[dict[str, object]]:
        nonlocal counter
        counter += 1
        return [{"_id": f"Vendor.App{counter}", "name": "Changing"}]

    monkeypatch.setattr(client, "_fetch_complete_catalog_once", changing_pass)

    with pytest.raises(WinstallCatalogUnstableError):
        await client.catalog_snapshot(max_attempts=3)
    await client._client.aclose()  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_complete_catalog_accepts_short_final_page(monkeypatch: pytest.MonkeyPatch) -> None:
    """La última página puede tener menos de 60 filas sin perder el total."""
    client = WinstallClient(Settings(), httpx.AsyncClient())

    async def page(offset: int, _limit: int) -> dict[str, object]:
        rows = [
            {"_id": f"Vendor.App{index}", "name": f"App {index}"}
            for index in range(offset, min(offset + 60, 61))
        ]
        return {"total": 61, "offset": offset, "limit": 60, "data": rows}

    monkeypatch.setattr(client, "_fetch_catalog_page", page)

    rows = await client._fetch_complete_catalog_once()

    assert len(rows) == 61
    assert rows[-1]["_id"] == "Vendor.App60"
    await client._client.aclose()  # type: ignore[union-attr]


@pytest.mark.asyncio
async def test_complete_catalog_rejects_duplicate_ids(monkeypatch: pytest.MonkeyPatch) -> None:
    """Un offset desplazado nunca se declara como snapshot completo."""
    client = WinstallClient(Settings(), httpx.AsyncClient())

    async def page(_offset: int, _limit: int) -> dict[str, object]:
        return {
            "total": 2,
            "offset": 0,
            "limit": 60,
            "data": [
                {"_id": "Vendor.Duplicate", "name": "First"},
                {"_id": "Vendor.Duplicate", "name": "Second"},
            ],
        }

    monkeypatch.setattr(client, "_fetch_catalog_page", page)

    with pytest.raises(WinstallCatalogIncompleteError, match="duplicate"):
        await client._fetch_complete_catalog_once()
    await client._client.aclose()  # type: ignore[union-attr]


def test_extract_winstall_download_links_from_app_page() -> None:
    """Comprueba el escenario `extract_winstall_download_links_from_app_page`.
    """
    html = """
    <ul>
      <li>
        <a href="https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-win64.exe">
          Download (.nullsoft)
        </a>
      </li>
      <li><a href="https://github.com/bibletime/bibletime">View Site</a></li>
    </ul>
    """

    downloads = extract_winstall_downloads(html, "https://winstall.app/apps/BibleTime.BibleTime")

    assert [download.url for download in downloads] == [
        "https://github.com/bibletime/bibletime/releases/download/v3.1.1/BibleTime-3.1.1-win64.exe"
    ]
    assert downloads[0].label == "Download (.nullsoft)"


def test_extract_winstall_page_links_finds_view_site_and_download() -> None:
    """Comprueba el escenario `extract_winstall_page_links_finds_view_site_and_download`.
    """
    html = (
        "<ul>"
        '<li><a href="https://technology.a-sit.at/pdf-over-2/?ref=winstall">'
        "View Site</a></li>"
        '<li><a href="https://github.com/microsoft/winget-pkgs">'
        "Source code for winget package</a></li>"
        '<li><a href="https://technology.a-sit.at/wp-content/uploads/2026/03/'
        'PDF-Over-4.4.8.msi">Download (.msi)</a></li>'
        "</ul>"
    )

    links = extract_winstall_page_links(html, "https://winstall.app/apps/A-SIT.PDF-Over")

    assert links.official_url == "https://technology.a-sit.at/pdf-over-2/?ref=winstall"
    assert links.source_code_url == "https://github.com/microsoft/winget-pkgs"
    assert [download.url for download in links.downloads] == [
        "https://technology.a-sit.at/wp-content/uploads/2026/03/PDF-Over-4.4.8.msi"
    ]


def test_winstall_version_link_ending_in_appx_is_not_a_download() -> None:
    """Comprueba el escenario `winstall_version_link_ending_in_appx_is_not_a_download`.
    """
    html = """
    <a href="/apps/KDE.Filelight.AppX">v25.1202.1987.0</a>
    <a href="https://cdn.kde.org/filelight-sideload.appx">Download (.msix)</a>
    """

    downloads = extract_winstall_downloads(
        html,
        "https://winstall.app/apps/KDE.Filelight.AppX",
    )

    assert [download.url for download in downloads] == [
        "https://cdn.kde.org/filelight-sideload.appx"
    ]
