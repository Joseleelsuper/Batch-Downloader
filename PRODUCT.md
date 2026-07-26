# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Batch Downloader sirve a personas que quieren descubrir y descargar aplicaciones en lote. El área administrativa está dirigida a operadores que mantienen el catálogo, supervisan el scraper y configuran servicios internos como la búsqueda semántica.

## Product Purpose

Centralizar un catálogo descargable y reproducible, permitir selecciones individuales o por bundles y mantener herramientas operativas para que el catálogo y sus instaladores sigan siendo fiables.

## Positioning

El producto une descubrimiento, validación de instaladores y descargas por lote con un catálogo cuyo estado operativo sigue siendo auditable. MySQL es la autoridad del catálogo y los servicios especializados mantienen proyecciones reconstruibles.

## Operating Context

La aplicación se ejecuta como un conjunto de servicios Docker Compose. El administrador trabaja desde una interfaz web en español y necesita distinguir con claridad el estado actual, los trabajos en curso y las acciones que cambian producción.

## Capabilities and Constraints

- El catálogo público permite búsqueda literal o semántica, filtros, bundles y descargas por sistema operativo.
- PostgreSQL con pgvector contiene una proyección semántica reconstruible; MySQL conserva filtros, estados, facetas y paginación.
- Los cambios de modelo deben conservar el modelo activo hasta que el candidato esté evaluado, indexado y activado explícitamente.
- La primera versión de administración semántica admite modelos base públicos de Hugging Face compatibles con SentenceTransformers y `safetensors`; no admite modelos privados, gated, código remoto, LoRA ni entrenamiento.
- Las rutas `/api/admin/**` requieren rol de administrador, CSRF y auditoría.

## Brand Commitments

El producto se llama Batch Downloader. La interfaz usa español como idioma principal, controles familiares y una identidad operativa sobria basada en teal, superficies claras y estados semánticos inequívocos.

## Evidence on Hand

- Catálogo, bundles y estado del scraper reales disponibles en los servicios existentes.
- Tres modelos de embeddings registrados y un índice E5 completo.
- Evaluador reproducible con métricas de calidad, latencia y recursos.
- No deben inventarse resultados de benchmarks ni capacidades de un modelo antes de medirlas.

## Product Principles

- La autoridad de los datos debe permanecer explícita.
- Una operación incompleta nunca debe cambiar silenciosamente producción.
- Las comparaciones requieren evidencia reproducible y compatible.
- Los fallos de servicios especializados deben degradar de forma segura.
- La interfaz administrativa debe explicar el estado y la recuperación posible.

## Accessibility & Inclusion

Las superficies operativas deben funcionar con teclado, conservar foco visible, exponer tablas y estados con semántica accesible y no depender únicamente del color.
