package com.tripbus.catalog.config;

import com.tripbus.catalog.common.security.InternalTokenFilter;
import com.tripbus.catalog.common.security.JsonSecurityHandlers;
import com.tripbus.catalog.common.security.JwtRoleConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JsonSecurityHandlers handlers,
            @Value("${tripbus.internal-token}") String internalToken) throws Exception {
        // CSRF no aplica: la API es sin estado (sin sesión ni cookies) y se autentica con el JWT del header
        // Authorization o con X-Internal-Token, que el navegador nunca adjunta solo a una petición de otro sitio.
        // Con CSRF activo, todo POST/PUT/PATCH con bearer token respondería 403.
        http
                .csrf(AbstractHttpConfigurer::disable) // NOSONAR java:S4502 API stateless con bearer token
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/**").hasRole(InternalTokenFilter.INTERNAL_ROLE)
                        // Búsqueda, viajes en ruta (seguimiento sin tiquete) y consulta de un viaje son públicas,
                        // igual que las paradas de una ruta (las usa fleet). GET /itineraries y /routes (listados
                        // de la empresa, US-114/US-116) exigen token.
                        .requestMatchers(HttpMethod.GET, "/trips/search", "/trips/cities", "/trips/in-progress",
                                "/itineraries/*", "/itineraries/*/seatmap", "/itineraries/*/price",
                                "/routes/*/stops").permitAll()
                        // Swagger (en prod springdoc está apagado y estas rutas dan 404) y página de error.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(new JwtRoleConverter()))
                        .authenticationEntryPoint(handlers.entryPoint()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .addFilterBefore(new InternalTokenFilter(internalToken), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
