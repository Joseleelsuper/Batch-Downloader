# Catálogos de traducción

Los mensajes se organizan por página o superficie de la aplicación web:

- `template/<pagina>.json` define las claves admitidas.
- `es/<pagina>.json` contiene los textos en español de esa misma página.

Cada archivo de `template` debe tener un archivo homónimo en cada idioma. Las claves de ambos
deben coincidir, no pueden repetirse entre páginas y las traducciones deben ser textos no vacíos.
El servicio valida y fusiona todos los archivos al arrancar, por lo que el contrato HTTP continúa
siendo un único catálogo plano en `/api/v1/locales/es`.
