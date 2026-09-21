package es.ubu.batchdownloader.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.ApiError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Configura autorización por rutas y roles, mutaciones con CSRF, renovación de sesión y capacidad
 * acotada de BCrypt para Core.
 *
 * @see es.ubu.batchdownloader.identity.api.IdentityController
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @see es.ubu.batchdownloader.identity.infrastructure.security.BoundedPasswordEncoder
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /**
     * Define acceso administrativo y de usuario, deja la propiedad de descargas a su caso de uso y
     * protege las mutaciones de navegador con CSRF.
     * La ruta interna de metadatos comprueba su credencial en el controlador y se excluye de CSRF y
     * redirección HTTPS.
     *
     * @param http Constructor de la cadena de filtros de seguridad HTTP de Spring.
     * @param objectMapper Serializador de las respuestas JSON seguras de acceso denegado o falta de
     *     sesión.
     * @param csrfTokens Repositorio de tokens CSRF utilizado al comprobar mutaciones y renovar la
     *     sesión.
     * @param requireHttps Redirige a HTTPS las rutas públicas; la ruta interna de metadatos
     *     conserva transporte interno.
     * @return cadena HTTP sin formularios ni autenticación básica, con errores JSON seguros.
     * @throws Exception si Spring no puede construir la cadena configurada.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokens,
            @Value("${app.security.require-https}") boolean requireHttps) throws Exception {
        RequestMatcher internalDownloadMetadata = PathPatternRequestMatcher.withDefaults().matcher(
                HttpMethod.POST, "/internal/v1/download-jobs/{jobId}/item-metadata");
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(internalDownloadMetadata))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login").permitAll()
                        .requestMatchers("/api/v1/admin/auth/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(internalDownloadMetadata).permitAll()
                        .requestMatchers("/api/v1/download-jobs/**").permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/apps/**", "/api/v1/bundles/**").permitAll()
                        .requestMatchers(
                                "/api/health", "/actuator/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    new ApiError("unauthorized", "Debes iniciar sesión.", Map.of()));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    new ApiError("forbidden", "No tienes permisos para esta operación.", Map.of()));
                        }));
        if (requireHttps) {
            http.redirectToHttps(redirect -> redirect
                    .requestMatchers(new NegatedRequestMatcher(internalDownloadMetadata)));
        }
        return http.build();
    }

    /**
     * Guarda el token CSRF en una cookie legible por el cliente con alcance en la raíz.
     *
     * @return repositorio de tokens usado por la cadena y la renovación de sesión.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    /**
     * Combina cambio de identificador de sesión y renovación del token CSRF al autenticarse.
     *
     * @param csrfTokens Repositorio de tokens CSRF utilizado al comprobar mutaciones y renovar la
     *     sesión.
     * @return estrategia que aplica ambas medidas en orden.
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokens)));
    }

    /**
     * Envuelve BCrypt con coste configurable en un pool con concurrencia, cola y espera acotadas.
     *
     * @param strength Coste de BCrypt configurado para generar hashes de contraseña.
     * @param concurrency Número fijo de cálculos criptográficos que pueden ejecutarse
     *     simultáneamente.
     * @param queueCapacity Máximo de cálculos en espera antes de rechazar por falta de capacidad.
     * @param wait Plazo máximo de espera por el resultado, incluyendo el tiempo en cola.
     * @return codificador que se cierra junto al contexto de Spring.
     */
    @Bean(destroyMethod = "close")
    PasswordEncoder passwordEncoder(
            @Value("${app.auth.bcrypt-strength}") int strength,
            @Value("${app.auth.hash-concurrency}") int concurrency,
            @Value("${app.auth.hash-queue}") int queueCapacity,
            @Value("${app.auth.hash-wait}") Duration wait) {
        return new BoundedPasswordEncoder(
                new BCryptPasswordEncoder(strength), concurrency, queueCapacity, wait);
    }

    /**
     * Persiste la autenticación mediante la sesión HTTP gestionada por Spring.
     *
     * @return repositorio del contexto de seguridad por sesión.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
