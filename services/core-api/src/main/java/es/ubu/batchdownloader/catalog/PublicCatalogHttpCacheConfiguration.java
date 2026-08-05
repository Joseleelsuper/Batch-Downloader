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
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Configura validación HTTP y caché corta para recursos públicos. */
@Configuration
class PublicCatalogHttpCacheConfiguration {
    /** Añade ETag a catálogo y bundles sin cambiar sus cuerpos JSON. */
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

    /** Añade directivas de caché únicamente a las lecturas públicas correctas. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> publicCacheControlFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                chain.doFilter(request, response);
                if ("GET".equals(request.getMethod())
                        && response.getStatus() < 400
                        && request.getUserPrincipal() == null) {
                    response.setHeader(
                            "Cache-Control", "public, max-age=5, stale-while-revalidate=15");
                } else {
                    response.setHeader("Cache-Control", "private, no-store");
                }
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
