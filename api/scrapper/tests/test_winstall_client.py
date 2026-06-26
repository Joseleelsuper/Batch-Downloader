from app.scraper.winstall import extract_next_data, parse_winstall_app


def test_extract_next_data_app_payload() -> None:
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
    assert app.installer_urls == ["https://cdn.example.com/EpicInstaller.msi"]
