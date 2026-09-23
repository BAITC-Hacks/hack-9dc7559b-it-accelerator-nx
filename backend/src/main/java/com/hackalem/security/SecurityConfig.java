package com.hackalem.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Каркас безопасности: stateless + CORS для фронта, пока всё открыто.
 *
 * Когда появится JWT (jjwt уже в зависимостях):
 *   1) security/JwtService — выпуск и разбор токена (секрет из app.jwt.secret);
 *   2) security/JwtAuthenticationFilter extends OncePerRequestFilter;
 *   3) здесь: .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
 *      и .anyRequest().authenticated(), оставив permitAll для списка ниже.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Origin'ы фронта: app.cors.allowed-origins ← env CORS_ALLOWED_ORIGINS. */
    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter, com.fasterxml.jackson.databind.ObjectMapper json) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((req,res,e) -> {
                        res.setStatus(401); res.setContentType("application/problem+json");
                        json.writeValue(res.getOutputStream(), com.hackalem.web.ApiErrors.problem(401,"unauthorized").getBody());
                    })
                    .accessDeniedHandler((req,res,e) -> {
                        res.setStatus(403); res.setContentType("application/problem+json");
                        json.writeValue(res.getOutputStream(), com.hackalem.web.ApiErrors.problem(403,"forbidden").getBody());
                    }))
                .authorizeHttpRequests(auth -> auth
                        // Публично и после включения JWT: health (healthcheck в compose) и docs.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/auth/visitor-session", "/api/ping",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/products", "/api/products/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID"));
        config.setExposedHeaders(List.of("Retry-After", "X-Request-Id"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
