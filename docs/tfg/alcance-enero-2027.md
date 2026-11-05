# Cierre funcional para enero de 2027

El depósito está previsto para el 12–14 de enero de 2027 y la defensa para el 18–22. El objetivo interno de cierre es el 8 de enero. Las fechas prospectivas y el trabajo pendiente se mantienen en [Project 6](https://github.com/users/Joseleelsuper/projects/6/views/5).

## Decisiones del autor del 5 de noviembre de 2026

- La entrega incluye personalización temporal, clonación, solicitudes de software y propuestas de bundles a partir de búsquedas semánticas.
- Se permiten estrellas en bundles propios públicos. Se conserva la unicidad por cuenta y bundle y se impiden las valoraciones de bundles privados. La [issue #18](https://github.com/Joseleelsuper/Batch-Downloader/issues/18) registra la sustitución del requisito original.
- La propuesta utiliza el modelo local de embeddings y los resultados del catálogo. No amplía las llamadas a OpenAI, DeepSeek o Groq.
- Los cambios se realizan directamente en `development`, por indicación expresa del autor. Las referencias a commits sustituyen los enlaces a PR cuando no existe un PR real.

## Selecciones editables

Desde el detalle de un bundle se puede abrir **Personalizar selección**. La selección se conserva únicamente en el estado de la página. Añadir, retirar o reordenar aplicaciones no cambia el bundle original. La descarga envía los `appIds` al mismo flujo de trabajos que una selección del catálogo, con sus validaciones y límites.

Una cuenta `USER` puede clonar un bundle público u oficial: revisa la selección, elige el nombre y confirma **Guardar como bundle privado**. La creación utiliza el endpoint personal existente, genera una nueva identidad y asigna la propiedad a la cuenta autenticada. El original conserva sus items. El detalle de origen solo incluye aplicaciones activas; una aplicación retirada entre la revisión y el guardado vuelve a validarse en Core y puede impedir el guardado. La copia puede contener aplicaciones activas sin instalador, pero su descarga sigue la política vigente de disponibilidad y alternativas manuales.

Al buscar en modo semántico aparece **Proponer bundle con búsqueda semántica**. Se consultan hasta doce resultados por relevancia, conservando etiquetas, mínimo de coincidencia, arquitectura, editor y sistemas operativos. La propuesta rechaza identificadores o plataformas que incumplan la estructura del catálogo, deduplica y excluye aplicaciones no descargables. El usuario puede modificarla antes de descargar o guardar. Las descripciones y etiquetas del catálogo permiten inspeccionar por qué aparecen los resultados, sin inventar capacidades de las aplicaciones.

Si la búsqueda aplica respaldo literal, no se presenta ese resultado como una propuesta semántica: se informa de la indisponibilidad y se permite seleccionar aplicaciones manualmente. El modo de propuesta y la personalización temporal no se indexan como páginas públicas.

## Evidencias iniciales

| Comportamiento | Evidencia |
| --- | --- |
| Descarga anónima de una selección modificada, sin persistencia | `BundleDraftPage.test.tsx` |
| Copia privada confirmada, independiente del original | `BundleDraftPage.test.tsx`, `UserBundleRepositoryTest` |
| Propuestas válidas, vacías, duplicadas e inválidas y respaldo manual | `semanticBundleProposal.test.ts`, `BundleDraftPage.test.tsx` |
| Arquitectura y mínimo de etiquetas combinados en MySQL | `CatalogTagMinimumMySqlTest` |
| Ambos sentidos, empates, paginación y alfabeto de bundles | `BundleCatalogMySqlTest` |
| Estrellas propias, unicidad concurrente y privacidad | `BundleCatalogMySqlTest`, `BundleStarSecurityTest` |
| Errores legibles que conservan códigos técnicos | `downloads/failures.test.ts`, `GlobalDownloadJobOverlay.test.tsx` |

La suite del frontend ha pasado con 344 pruebas y 88,41 % de cobertura de líneas, usando dos procesos para limitar la competencia con Docker. El contrato principal y el perfil público de IA se comprueban conjuntamente con las catorce pruebas de `scripts/quality/tests`. Estos resultados locales no acreditan por sí solos un despliegue de producción ni sustituyen la validación final del flujo completo.

## Trabajo que sigue abierto

El resumen general previo a descargar (#5), las solicitudes de incorporación de software (#20), el cierre de observabilidad (#43) y las tareas académicas #111–#117 siguen sujetos a sus criterios y evidencias. Las issues se cerrarán conforme se verifique su resultado; las fechas objetivo no representan fechas de finalización real.
