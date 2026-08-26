"""Comprueba en vivo los contratos mínimos de Winstall sin descargar instaladores."""

from __future__ import annotations

import argparse
import asyncio
import json
from typing import Any

import httpx

DEFAULT_BASE_URL = "https://winstall.app"
DEFAULT_PACKAGE_ID = "Valve.Steam"


def require(condition: bool, code: str) -> None:
    """Interrumpe la canaria con un código estable y sin incluir URLs sensibles."""
    if not condition:
        raise RuntimeError(code)


async def check(base_url: str, package_id: str) -> dict[str, Any]:
    """Valida catálogo, detalle, búsqueda exacta y el HTML reducido actual."""
    headers = {"User-Agent": "BatchDownloaderScraper/0.1"}
    async with httpx.AsyncClient(
        timeout=20,
        follow_redirects=True,
        headers=headers,
    ) as client:
        catalog_response = await client.get(
            f"{base_url}/api/winstall/apps",
            params={"offset": 0, "limit": 2},
        )
        catalog_response.raise_for_status()
        catalog = catalog_response.json()
        require(isinstance(catalog, dict), "catalog_not_object")
        require(isinstance(catalog.get("total"), int), "catalog_total_missing")
        require(isinstance(catalog.get("data"), list), "catalog_data_missing")

        detail_response = await client.get(
            f"{base_url}/api/winstall/apps/{package_id}"
        )
        detail_response.raise_for_status()
        detail = detail_response.json()
        require(isinstance(detail, dict), "detail_not_object")
        require((detail.get("_id") or detail.get("id")) == package_id, "detail_id_mismatch")
        versions = detail.get("versions")
        require(isinstance(versions, list), "detail_versions_missing")
        require(
            all(
                isinstance(version, dict)
                and isinstance(version.get("installers"), list)
                for version in versions
            ),
            "detail_installers_incomplete",
        )

        search_response = await client.get(
            f"{base_url}/api/winstall/apps/search",
            params={"q": package_id, "offset": 0, "limit": 60},
        )
        search_response.raise_for_status()
        search = search_response.json()
        search_rows = search.get("data") if isinstance(search, dict) else None
        require(isinstance(search_rows, list), "search_data_missing")
        require(
            any(
                isinstance(row, dict)
                and (row.get("_id") or row.get("id")) == package_id
                for row in search_rows
            ),
            "search_exact_match_missing",
        )

        html_response = await client.get(f"{base_url}/apps/{package_id}")
        html_response.raise_for_status()
        require('id="__NEXT_DATA__"' in html_response.text, "next_data_missing")

    return {
        "status": "ok",
        "catalogTotal": catalog["total"],
        "catalogRowsChecked": len(catalog["data"]),
        "packageId": package_id,
        "detailVersions": len(versions),
        "detailInstallers": sum(len(version["installers"]) for version in versions),
        "searchExactMatch": True,
        "htmlIsInstallerAuthority": False,
    }


async def main() -> None:
    """Ejecuta la canaria y emite únicamente diagnóstico estructural."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--package-id", default=DEFAULT_PACKAGE_ID)
    arguments = parser.parse_args()
    result = await check(arguments.base_url.rstrip("/"), arguments.package_id)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
