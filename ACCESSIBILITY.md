# Accesibilidad

## Objetivo y alcance

El objetivo de Batch-Downloader es que las personas puedan consultar el catálogo,
preparar descargas y utilizar su cuenta con distintos métodos de interacción.
La referencia de trabajo es [WCAG 2.2, nivel AA](https://www.w3.org/TR/WCAG22/).
Esta declaración se refiere a la interfaz web mantenida en este repositorio.

**No se ha evaluado la conformidad completa con WCAG.** Las comprobaciones existentes
cubren recorridos concretos y no acreditan que toda la aplicación sea accesible.

## Estado de la evaluación

La [guía de pruebas](docs/accessibility/README.md) recoge la matriz de recorridos,
los resultados fechados y el procedimiento para repetirlos:

- Catálogo, selección, descargas y progreso; bundles públicos y selectores de instaladores.
- Acceso por correo y creación de bundles desde la cuenta.
- Pruebas de componentes y recorridos de teclado en Chromium, en ventanas de escritorio y estrechas.

Las páginas e instaladores de los proveedores externos tienen sus propias condiciones de
accesibilidad y está fuera de mi alcance cubrirlas.

## Pautas al modificar la interfaz

- Permitir el uso con teclado, con foco visible, orden lógico y salida de los diálogos
  mediante Escape; devolver el foco al control que los abrió.
- Usar HTML semántico, etiquetas y nombres comprensibles; comunicar el estado de los controles
  y los cambios de carga, progreso, éxito o error a las tecnologías de apoyo.
- Asociar los errores con sus campos, ofrecer instrucciones claras y no depender solo del color.
- Revisar contraste, ampliación del texto, ventanas estrechas y preferencias de movimiento reducido.
- Combinar las pruebas automáticas con revisión visual, teclado y lector de pantalla.
  Registrar cada resultado como comprobado o pendiente, con su fecha y entorno.

Los comandos y los pasos manuales están en la [guía técnica](docs/accessibility/README.md#ejecución).

## Comunicar una barrera

Abre una [incidencia de accesibilidad](https://github.com/Joseleelsuper/Batch-Downloader/issues/new)
o escribe a [JoseGallardoC@protonmail.com](mailto:JoseGallardoC@protonmail.com) con el asunto
«Accesibilidad — Batch-Downloader».

Indica la página o flujo, versión o fecha, pasos, resultado esperado y observado, navegador,
sistema operativo y método de interacción. Si utilizas lector de pantalla, incluye su nombre,
versión e idioma y, si es útil, el texto anunciado. No hace falta indicar diagnósticos médicos
ni publicar datos personales, enlaces de acceso o capturas con información de tu cuenta.

Si el problema también afecta a permisos o datos, sigue la [política de seguridad](SECURITY.md)
y comunícalo en privado.

Referencia de formato: [declaraciones de accesibilidad de W3C WAI](https://www.w3.org/WAI/planning/statements/).
