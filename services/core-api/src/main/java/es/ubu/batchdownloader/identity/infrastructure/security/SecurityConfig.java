package es.ubu.batchdownloader.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.ApiError;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Seguridad de sesión compartida con flujos de credenciales separados por rol. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            CsrfTokenRepository csrfTokens,
            GoogleOAuthSuccessHandler oauthSuccessHandler,
            GoogleOAuthFailureHandler oauthFailureHandler,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${app.security.require-https}") boolean requireHttps) throws Exception {
        RequestMatcher internalDownloadMetadata = new AntPathRequestMatcher(
                "/internal/v1/download-jobs/*/item-metadata", HttpMethod.POST.name());
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(internalDownloadMetadata))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login").permitAll()
                        .requestMatchers("/api/admin/auth/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(internalDownloadMetadata).permitAll()
                        .requestMatchers("/api/v1/download-jobs/**").permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("USER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/auth/preferences").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/apps/**", "/api/apps/**",
                                "/api/v1/bundles/**", "/api/bundles/**").permitAll()
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
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .authorizationEndpoint(endpoint -> endpoint
                            .baseUri("/api/v1/auth/oauth2/authorization"))
                    .redirectionEndpoint(endpoint -> endpoint
                            .baseUri("/api/v1/auth/oauth2/callback/*"))
                    .successHandler(oauthSuccessHandler)
                    .failureHandler(oauthFailureHandler));
        }
        if (requireHttps) {
            http.requiresChannel(channel -> channel
                    .requestMatchers(new NegatedRequestMatcher(internalDownloadMetadata))
                    .requiresSecure());
        }
        return http.build();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokens)));
    }

    @Bean(destroyMethod = "close")
    PasswordEncoder passwordEncoder(
            @Value("${app.auth.bcrypt-strength}") int strength,
            @Value("${app.auth.hash-concurrency}") int concurrency,
            @Value("${app.auth.hash-queue}") int queueCapacity,
            @Value("${app.auth.hash-wait}") Duration wait) {
        return new BoundedPasswordEncoder(
                new BCryptPasswordEncoder(strength), concurrency, queueCapacity, wait);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
