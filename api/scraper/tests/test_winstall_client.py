from app.scraper.winstall import extract_next_data, extract_winstall_downloads, parse_winstall_app


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
    assert app.tags == ["games"]
    assert app.installer_urls == ["https://cdn.example.com/EpicInstaller.msi"]


def test_extract_winstall_download_links_from_app_page() -> None:
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
