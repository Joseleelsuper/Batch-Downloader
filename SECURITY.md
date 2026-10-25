# Política de seguridad

## Comunicar una vulnerabilidad

Escribe a [JoseGallardoC@protonmail.com](mailto:JoseGallardoC@protonmail.com) con el asunto
«Seguridad — Batch-Downloader». Es el contacto publicado por el servicio.

Incluye, cuando sea posible:

- Versión o commit, servicio o ruta afectada y entorno de ejecución.
- Pasos mínimos para reproducirlo, resultado esperado y observado.
- Impacto posible, permisos y condiciones necesarios.
- Evidencia con datos ficticios y sin contraseñas, cookies, tokens, enlaces de acceso ni datos personales.
- Si procede de un escáner: CVE o identificador del aviso, dependencia y versión resuelta.

## Alcance y versiones

Esta política cubre el código y la configuración mantenidos en este repositorio:
web, servicios Java, scraper, búsqueda semántica, instalador Linux generado,
almacenamiento, contenedores y automatización de construcción y despliegue.

El flujo de despliegue toma `main` como referencia; `development` puede contener
cambios aún no publicados. Indica siempre la versión o el commit observado, también si es
antiguo, para comprobar si el problema sigue vigente.

## Propiedades que deben mantenerse

Estos requisitos orientan la revisión; su inclusión no acredita una auditoría completa.

- **Identidad y propiedad:** separar acceso público, usuario y administración; comprobar
  la propiedad de cuentas, bundles privados, trabajos y archivos, también en descargas anónimas.
  Las operaciones mutables del navegador deben mantener su protección CSRF.
- **Servicios internos:** validar las credenciales entre servicios y no entregarlas al navegador.
  Las interfaces operativas, bases de datos, broker y almacenes deben restringirse por red.
- **Entradas externas:** tratar URLs, metadatos del catálogo y archivos como datos no fiables.
  Las descargas no deben permitir acceder a redes privadas, escapar de los directorios de trabajo
  o ejecutar comandos arbitrarios; deben respetar límites de tamaño, tiempo y capacidad.
- **Datos y secretos:** evitar la exposición de sesiones, enlaces de acceso, destinatarios y
  credenciales en respuestas, logs o artefactos; mantener la limpieza y el aislamiento de datos.
- **Dependencias y distribución:** revisar bibliotecas, runtimes, imágenes y scripts de instalación.
  Un aviso de dependencia se evalúa con su versión, configuración y ruta de uso; no se descarta
  por proceder de una herramienta automática ni se considera explotable solo por su presencia.

Comunica accesos indebidos, filtraciones, ejecución de código o fallos de disponibilidad con
impacto en el proyecto. Si interviene un tercero, explica cómo lo usa o configura Batch-Downloader.
Los problemas de accesibilidad tienen su [propio canal](ACCESSIBILITY.md); si también exponen
datos o eluden permisos, utiliza el canal privado de seguridad.

## Operación y mantenimiento

Para configurar una instancia, consulta el [README](README.md), la
[guía de producción](docs/deployment/produccion.md) y la
[política de conservación](docs/privacy-retention.md).

Referencia de formato: [políticas de seguridad en GitHub](https://docs.github.com/en/code-security/getting-started/adding-a-security-policy-to-your-repository).
