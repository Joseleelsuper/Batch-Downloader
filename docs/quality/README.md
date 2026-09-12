# Mantenibilidad y documentación

Línea base: `a2c32e0`. El inventario incluye código propio, pruebas y migraciones;
excluye configuración, catálogos de traducción, contratos declarativos y artefactos generados.
Las herramientas y el código compartido cuentan en el balance de producción.

```powershell
.\.venv\Scripts\python.exe scripts/source_inventory.py --ref a2c32e0 --output docs/quality/baseline.json
.\.venv\Scripts\python.exe scripts/source_inventory.py --baseline docs/quality/baseline.json --output docs/quality/current.json --check
```

La clasificación usa Pygments 2.20.0 y AST Python. Las líneas mixtas cuentan como código.
Una extracción entre archivos no constituye reducción. Las cifras de cobertura y rendimiento
se publican únicamente con su ejecución y evidencia; los umbrales configurados no son resultados.

## Contrato documental

Documentar propósito, resultado observable, invariantes, efectos y errores que se propagan.
Los parámetros explican significado, unidades y valores especiales. Las pruebas describen el
escenario y la garantía. Evitar repetir el identificador como explicación.

- Java: `@see`/`{@link}` para colaboradores reales; `@since` para incorporación verificable;
  `@version` para versión del módulo en tipos y paquetes; `@category` para contexto funcional.
- `{@inheritDoc}` solo hereda un contrato completo y aplicable.
- Python: docstrings con `Args`, `Returns`, `Raises` y referencias cuando aportan información.
- TypeScript: JSDoc; conservar tipos en las firmas y explicar resultados, cancelación y efectos.
- Conservar autores y bytes de migraciones Flyway históricas. Su documentación complementaria
  se vincula por versión; las migraciones Python conservan AST funcional e identificadores.

## Lotes de implementación

- [ ] Inventario, contratos HTTP y comprobaciones documentales.
- [ ] Scraper: casos de uso, repositorios, coordinación y complejidad.
- [ ] React: listado, edición, inspección, descubrimiento y CSS.
- [ ] Core: parámetros, proyecciones y casos de uso de descargas.
- [ ] Worker: composición, puerto URI e instalador Linux.
- [ ] Semantic: composición de almacenes, rutas, contexto y entrenamiento.
- [ ] Notificaciones, traducciones y documentación transversal.
- [ ] Validación final y balance de líneas.

Las pruebas integradas utilizan datos y contenedores efímeros. Esta campaña conserva los
despliegues existentes; no requiere reiniciar el stack del usuario.
