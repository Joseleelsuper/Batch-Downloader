package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agrupa contratos de listado, detalle y edición de bundles, distinguiendo edición administrativa y
 * personal con control de versión.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.bundle.BundleController
 * @see es.ubu.batchdownloader.bundle.UserBundleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
public class BundleDtos {
    /**
     * Describe cuántas aplicaciones ofrecen un instalador seleccionable por plataforma y presenta
     * una muestra de hasta seis.
     *
     * @param operatingSystem Plataforma concreta: windows, linux o macos.
     * @param downloadableAppCount Número de aplicaciones con un instalador seleccionable para esta
     *     plataforma.
     * @param previewApps Hasta seis aplicaciones de muestra, conservando el orden configurado del
     *     bundle.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record PlatformAvailability(
            String operatingSystem,
            int downloadableAppCount,
            List<AppListItem> previewApps) {}

    /**
     * Entrega metadatos, etiquetas y muestras de un bundle para listas públicas o administrativas.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param starCount Estrellas registradas para ordenar y presentar el bundle.
     * @param appCount Número de aplicaciones del bundle; las proyecciones públicas cuentan las
     *     activas.
     * @param operatingSystems Plataformas con al menos una aplicación que tiene instalador
     *     seleccionable.
     * @param platformAvailability Recuentos y muestras por plataforma en orden windows, linux,
     *     macos.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param previewApps Hasta seis aplicaciones de muestra, conservando el orden configurado del
     *     bundle.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record BundleSummary(
            String id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            int appCount,
            List<String> operatingSystems,
            List<PlatformAvailability> platformAvailability,
            List<String> tags,
            List<AppListItem> previewApps,
            LocalDateTime updatedAt) {}

    /**
     * Entrega la selección ordenada completa de aplicaciones activas y su disponibilidad por
     * plataforma.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param starCount Estrellas registradas para ordenar y presentar el bundle.
     * @param appCount Número de aplicaciones del bundle; las proyecciones públicas cuentan las
     *     activas.
     * @param operatingSystems Plataformas con al menos una aplicación que tiene instalador
     *     seleccionable.
     * @param platformAvailability Recuentos y muestras por plataforma en orden windows, linux,
     *     macos.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param apps Aplicaciones activas del detalle en el orden configurado del bundle.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record BundleDetails(
            String id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            int appCount,
            List<String> operatingSystems,
            List<PlatformAvailability> platformAvailability,
            List<String> tags,
            List<AppListItem> apps,
            LocalDateTime updatedAt) {}

    /**
     * Acompaña el listado de bundles con su página, tamaño efectivo y total filtrado.
     *
     * @param data Bundles de la página solicitada, conservando el orden de consulta.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @param total Total que cumple el filtro antes de paginar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record BundleSearchResponse(List<BundleSummary> data, int page, int pageSize, long total) {}

    /**
     * Transporta una edición administrativa completa con metadatos, visibilidad, etiquetas y hasta
     * cien aplicaciones.
     *
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param appIds Identificadores o slugs de aplicaciones en el orden solicitado; se admite un
     *     máximo de cien.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record UpsertBundleRequest(
            @NotBlank String name,
            String description,
            String slug,
            String type,
            String visibility,
            List<String> tags,
            @Size(max = 100) List<String> appIds) {}

    /**
     * Resume un bundle personal e incluye la versión necesaria para evitar sobrescribir una edición
     * concurrente.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param appCount Número de aplicaciones del bundle; las proyecciones públicas cuentan las
     *     activas.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @param version Versión persistida que la siguiente edición personal debe devolver como
     *     expectedVersion.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record OwnBundleSummary(
            String id,
            String slug,
            String name,
            String description,
            String visibility,
            int appCount,
            List<String> tags,
            LocalDateTime updatedAt,
            long version) {}

    /**
     * Entrega al propietario metadatos, aplicaciones activas y versión de edición de su bundle.
     *
     * @param id UUID estable del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param appCount Número de aplicaciones del bundle; las proyecciones públicas cuentan las
     *     activas.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param apps Aplicaciones activas del detalle en el orden configurado del bundle.
     * @param updatedAt Fecha del último cambio persistido del bundle.
     * @param version Versión persistida que la siguiente edición personal debe devolver como
     *     expectedVersion.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record OwnBundleDetails(
            String id,
            String slug,
            String name,
            String description,
            String visibility,
            int appCount,
            List<String> tags,
            List<AppListItem> apps,
            LocalDateTime updatedAt,
            long version) {}

    /**
     * Pagina exclusivamente bundles de tipo user pertenecientes al UUID de la cuenta.
     *
     * @param data Bundles de la página solicitada, conservando el orden de consulta.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @param total Total que cumple el filtro antes de paginar.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record OwnBundlePage(
            List<OwnBundleSummary> data, int page, int pageSize, long total) {}

    /**
     * Acota nombre, descripción, slug, treinta etiquetas y cien aplicaciones; la creación personal
     * establece siempre visibilidad privada.
     *
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param appIds Identificadores o slugs de aplicaciones en el orden solicitado; se admite un
     *     máximo de cien.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record CreateOwnBundleRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 4000) String description,
            @Size(max = 180) String slug,
            @Size(max = 30) List<@NotBlank @Size(max = 80) String> tags,
            @Size(max = 100) List<@NotBlank String> appIds) {}

    /**
     * Transporta una edición personal con visibilidad explícita y la versión leída por el editor
     * para detectar conflictos.
     *
     * @param name Nombre visible del conjunto de aplicaciones.
     * @param description Descripción opcional de la finalidad del bundle.
     * @param slug Identificador legible del bundle dentro de las rutas públicas.
     * @param visibility Visibilidad public, private u official; las ediciones personales solo
     *     admiten public o private.
     * @param tags Etiquetas visibles del bundle, normalizadas para comparar y guardar según su
     *     flujo.
     * @param appIds Identificadores o slugs de aplicaciones en el orden solicitado; se admite un
     *     máximo de cien.
     * @param expectedVersion Versión leída por el editor; una versión antigua impide reemplazar
     *     datos e items.
     * @since 0.1.0
     * @version 0.1.0
     * @category Bundles
     */
    public record UpdateOwnBundleRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 4000) String description,
            @Size(max = 180) String slug,
            @NotBlank String visibility,
            @Size(max = 30) List<@NotBlank @Size(max = 80) String> tags,
            @Size(max = 100) List<@NotBlank String> appIds,
            @NotNull Long expectedVersion) {}
}
