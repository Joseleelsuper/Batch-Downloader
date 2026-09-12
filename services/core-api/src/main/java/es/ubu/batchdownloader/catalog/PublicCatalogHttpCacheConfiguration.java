package es.ubu.batchdownloader.catalog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Aplica ETag y caché breve a GET públicos anónimos del catálogo y bundles, manteniendo privadas
 * las respuestas autenticadas o fallidas.
 *
 * @see es.ubu.batchdownloader.catalog.CatalogController
 * @see es.ubu.batchdownloader.bundle.BundleController
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Configuration
class PublicCatalogHttpCacheConfiguration {
    /**
     * Registra el cálculo de ETag para rutas de aplicaciones y bundles después del filtro de
     * política de caché.
     *
     * @return filtro con orden veinte sobre ambas colecciones y sus recursos.
     */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> publicEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ShallowEtagHeaderFilter());
        registration.setUrlPatterns(List.of(
                "/api/v1/apps", "/api/v1/apps/*",
                "/api/v1/bundles", "/api/v1/bundles/*"));
        registration.setOrder(20);
        return registration;
    }

    /**
     * Registra una política de caché pública de cinco segundos y revalidación de quince para GET
     * anónimos correctos; las demás respuestas usan private, no-store.
     *
     * @return filtro con orden diez que conserva y copia el cuerpo de respuesta.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> publicCacheControlFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            /**
             * Captura la respuesta, decide caché según método, estado e identidad y copia el cuerpo
             * al cliente después de fijar las cabeceras.
             *
             * @param request Solicitud HTTP cuya identidad, método y respuesta determinan si admite
             *     caché pública.
             * @param response Respuesta HTTP en la que se establecen caché y contenido después de
             *     ejecutar la cadena.
             * @param chain Resto de filtros y controlador que produce la respuesta.
             * @throws jakarta.servlet.ServletException si falla la cadena de filtros o el
             *     controlador.
             * @throws java.io.IOException si falla la lectura o escritura HTTP.
             */
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
                chain.doFilter(request, wrapped);
                if ("GET".equals(request.getMethod())
                        && wrapped.getStatus() < 400
                        && (request.getUserPrincipal() == null
                                || request.getUserPrincipal() instanceof AnonymousAuthenticationToken)) {
                    wrapped.setHeader(
                            "Cache-Control", "public, max-age=5, stale-while-revalidate=15");
                } else {
                    wrapped.setHeader("Cache-Control", "private, no-store");
                }
                wrapped.copyBodyToResponse();
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setUrlPatterns(List.of(
                "/api/v1/apps", "/api/v1/apps/*",
                "/api/v1/bundles", "/api/v1/bundles/*"));
        registration.setOrder(10);
        return registration;
    }
}
