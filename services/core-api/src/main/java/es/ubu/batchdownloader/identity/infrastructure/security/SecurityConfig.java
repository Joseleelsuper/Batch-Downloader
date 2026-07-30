package es.ubu.batchdownloader.identity.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.ApiError;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Define la configuración utilizada por {@code SecurityConfig}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /**
     * Ejecuta la operación {@code securityFilterChain}.
     *
     * @param http Valor de {@code http} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param requireHttps Valor de {@code requireHttps} utilizado por la operación.
     * @return Resultado producido por {@code securityFilterChain}.
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            @Value("${app.security.require-https}") boolean requireHttps) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        RequestMatcher internalDownloadMetadata = new AntPathRequestMatcher(
                "/internal/v1/download-jobs/*/item-metadata",
                HttpMethod.POST.name());
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .ignoringRequestMatchers(internalDownloadMetadata))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(internalDownloadMetadata).permitAll()
                        .requestMatchers("/api/v1/download-jobs/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/auth/preferences").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/apps/**", "/api/apps/**", "/api/v1/bundles/**", "/api/bundles/**").permitAll()
                        .requestMatchers("/api/health", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
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
            http.requiresChannel(channel -> channel
                    .requestMatchers(new NegatedRequestMatcher(internalDownloadMetadata))
                    .requiresSecure());
        }
        return http.build();
    }

    /**
     * Ejecuta la operación {@code passwordEncoder}.
     *
     * @param strength Valor de {@code strength} utilizado por la operación.
     * @return Resultado producido por {@code passwordEncoder}.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.auth.bcrypt-strength}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    /**
     * Ejecuta la operación {@code userDetailsService}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @return Resultado producido por {@code userDetailsService}.
     */
    @Bean
    UserDetailsService userDetailsService(UserAccountStore users) {
        return username -> users.findByNormalizedUsername(username.strip().toLowerCase(java.util.Locale.ROOT))
                .map(account -> User.withUsername(account.username())
                        .password(account.passwordHash())
                        .roles(account.role().name())
                        .disabled(!account.enabled() || !account.emailVerified())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("user_not_found"));
    }

    /**
     * Ejecuta la operación {@code authenticationManager}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @param passwordEncoder Valor de {@code passwordEncoder} utilizado por la operación.
     * @return Resultado producido por {@code authenticationManager}.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Ejecuta la operación {@code securityContextRepository}.
     *
     * @return Resultado producido por {@code securityContextRepository}.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
