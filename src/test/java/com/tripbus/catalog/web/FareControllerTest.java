package com.tripbus.catalog.web;

import com.tripbus.catalog.domain.CategoriaAsiento;
import com.tripbus.catalog.domain.TipoPasajero;
import com.tripbus.catalog.service.FareService;
import com.tripbus.catalog.web.dto.FareDtos.SeasonRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** US-138/US-141: el OPERADOR edita y borra tarifas de la empresa de su token; el ADMIN indica la empresa. */
class FareControllerTest {

    private final FareService service = mock(FareService.class);
    private final FareController controller = new FareController(service);
    private final UUID empresa = UUID.randomUUID();

    @Test
    void editaYBorraTemporadasConLaEmpresaDelOperador() {
        UUID id = UUID.randomUUID();
        SeasonRequest request = new SeasonRequest(null, null, "Fin de año", LocalDate.of(2026, 12, 15),
                LocalDate.of(2027, 1, 10), new BigDecimal("1.2"));

        controller.updateSeason(id, request, operador());
        controller.deleteSeason(id, operador());

        verify(service).updateSeason(id, request, empresa);
        verify(service).deleteSeason(id, empresa);
    }

    @Test
    void borraDescuentosYRecargosConLaEmpresaQueIndicaElAdmin() {
        controller.deletePassengerType(TipoPasajero.NINO, empresa, admin());
        controller.deleteSurcharge(CategoriaAsiento.PREMIUM, null, operador());

        verify(service).deletePassengerType(TipoPasajero.NINO, empresa, null);
        verify(service).deleteSurcharge(CategoriaAsiento.PREMIUM, null, empresa);
    }

    private JwtAuthenticationToken operador() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(UUID.randomUUID().toString())
                .claim("companyId", empresa.toString()).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_OPERADOR")));
    }

    private static JwtAuthenticationToken admin() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(UUID.randomUUID().toString()).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
