package com.tripbus.catalog.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void corsSoloPermiteLosOrigenesConfiguradosYExponeElTotal() {
        var source = new SecurityConfig().corsConfigurationSource(
                " https://app.example.com, ,http://localhost:5173 ");
        var cors = source.getCorsConfiguration(new MockHttpServletRequest());

        assertThat(cors.getAllowedOrigins()).containsExactly("https://app.example.com", "http://localhost:5173");
        assertThat(cors.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(cors.getExposedHeaders()).containsExactly("X-Total-Count");
    }
}
