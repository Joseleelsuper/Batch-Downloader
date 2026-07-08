# Batch Downloader Scraper

Servicio interno Python para sincronizar el catalogo de Winstall, resolver instaladores
desde webs oficiales y exponer endpoints MVP de busqueda/descarga.

## Comandos

```bash
pip install -r requirements.txt
playwright install chromium
alembic upgrade head
uvicorn app.main:app
python -m app.worker scrape-once
python -m app.worker scheduler
pytest
```

El scheduler debe ejecutarse como proceso separado de FastAPI para evitar trabajos
duplicados cuando existan varias replicas.
