# Evaluación de accesibilidad de los flujos principales

Referencia: [WCAG 2.2, nivel AA](https://www.w3.org/TR/WCAG22/). Esta matriz cubre recorridos prioritarios del producto; no acredita conformidad de toda la web. No hay métricas de uso en el repositorio para ordenar los flujos por frecuencia real.

## Matriz de recorridos

| Flujo y pasos | Resultado esperado con teclado y lector | Criterios WCAG 2.2 | Entorno y resultado observado |
|---|---|---|---|
| Inicio → catálogo: llegar a «Abrir catálogo», buscar, cambiar filtros y abrir/cerrar un detalle | Tab, Shift+Tab y Enter/Space alcanzan cada acción; el foco es visible y conserva un orden lógico; lector anuncia nombre, estado de filtro y detalle expandido | 2.1.1, 2.4.1, 2.4.3, 2.4.7, 2.4.11, 4.1.2 | Chromium: recorrido automatizado completado en escritorio y móvil. NVDA/Chrome ES y EN: pendiente |
| Catálogo → seleccionar → descargar: marcar una aplicación, iniciar ZIP y consultar progreso o error | Se anuncia la selección, el botón de descarga y el estado del trabajo; el progreso tiene nombre; no aparece una trampa de teclado | 2.1.1, 2.1.2, 3.3.1, 4.1.2, 4.1.3 | Vitest y Chromium: selección y progreso comprobados. Anuncio real con NVDA: pendiente |
| Bundle público → elegir sistema → descargar; abrir un detalle o un selector Linux si procede | El sistema elegido y los instaladores tienen nombre y estado; diálogo con título, foco contenido y retorno al disparador al cerrarlo; mensajes de espera y error se anuncian | 2.1.1, 2.1.2, 2.4.3, 2.4.7, 4.1.2, 4.1.3 | Chromium: Escape y Cancelar devuelven el foco al botón; la cancelación no muestra error de descarga. NVDA/Chrome: pendiente |
| Acceso por correo → enviar enlace | Campo con etiqueta, error identificable y asociado al campo, confirmación perceptible sin mover el foco de forma inesperada | 3.3.1, 3.3.2, 4.1.2, 4.1.3 | Vitest y Chromium: alerta, asociación y estado inválido del correo, limpieza al editar y mensaje de éxito comprobados. Anuncio real con NVDA: pendiente |
| Cuenta → crear bundle propio: nombre, búsqueda, selección y guardado | Los controles y la lista de resultados tienen nombre; el foco sigue la selección y el guardado; estados, errores y confirmación se anuncian | 2.1.1, 2.4.3, 2.4.7, 3.3.1, 3.3.2, 4.1.2, 4.1.3 | Vitest y Chromium: creación con teclado y retorno del foco al cerrar el selector de etiquetas comprobados. NVDA/Chrome ES y EN: pendiente |

La cobertura automática usa respuestas simuladas de API y no prueba servicios reales. El 20/10/2026, Vitest completó 67/67 pruebas enfocadas; Chromium 153.0.8010.12 completó los recorridos sin barreras detectadas por el guion en 1280 × 800 y 390 × 844. Ninguna fila se ha probado todavía con NVDA.

**Barrera corregida:** 20/10/2026, Chromium 153, en 1280 × 800 y 390 × 844. En bundle público → descarga Linux → Escape, el foco quedaba en `BODY` en lugar de volver a «Descargar bundle (1)» (WCAG 2.4.3; [patrón de diálogo modal WAI-ARIA](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)). El botón se deshabilitaba antes de abrir el diálogo. Ahora se conserva el disparador, se devuelve el foco cuando vuelve a estar disponible y `npm run test:accessibility-keyboard` termina correctamente. El anuncio real con NVDA sigue pendiente.

## Ejecución

Desde `services/webapp/src/main/resources/frontend`:

```powershell
npm run test:accessibility-keyboard
npx vitest run src/App.test.tsx src/pages/account/AccountPages.test.tsx src/downloads/LinuxTargetDialog.test.tsx src/components/BundleTagPicker.test.tsx src/hooks/useDownloadJob.test.ts
```

El primer comando abre Chromium y recorre con teclado los flujos en 1280 × 800 y 390 × 844, sin Docker. Para la revisión manual, usar Chrome y NVDA en Windows, primero con la interfaz en español y después en inglés. Repetir cada fila sin ratón: recorrer con Tab/Shift+Tab, activar con Enter/Space, cerrar paneles con Escape y leer por encabezados, regiones y controles desde NVDA. Comprobar visualmente que el foco permanece visible y no queda oculto; escuchar el texto realmente anunciado, incluidos los cambios asíncronos. La [guía WAI de evaluación](https://www.w3.org/WAI/test-evaluate/tools/selecting/) recuerda que las herramientas automáticas requieren juicio humano.

Registrar barreras como `fecha | entorno | flujo/paso | esperado | observado | criterio WCAG | evidencia (captura o texto anunciado) | incidencia`. Marcar «pendiente» si no se ejecutó; no convertir un test de DOM o Chromium en un resultado atribuido a NVDA. La revisión del panel administrativo queda para una fase posterior.
