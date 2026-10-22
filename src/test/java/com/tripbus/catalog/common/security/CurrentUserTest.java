package com.tripbus.catalog.common.security;

import com.tripbus.catalog.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Claims del JWT de identity y alcance por empresa (ADMIN vs OPERADOR). */
class CurrentUserTest {

    private static final UUID USUARIO = UUID.randomUUID();
    private static final UUID EMPRESA = UUID.randomUUID();

    @Test
    void leeIdRolYEmpresaDelJwt() {
        Authentication auth = token("OPERADOR", EMPRESA);
        assertThat(CurrentUser.id(auth)).isEqualTo(USUARIO);
        assertThat(CurrentUser.role(auth)).isEqualTo("OPERADOR");
        assertThat(CurrentUser.companyId(auth)).isEqualTo(EMPRESA);
        assertThat(CurrentUser.hasRole(auth, "OPERADOR")).isTrue();
        assertThat(CurrentUser.hasRole(auth, "ADMIN")).isFalse();
    }

    @Test
    void sinJwtNoHayUsuarioNiClaims() {
        Authentication otra = new TestingAuthenticationToken("x", null, "ROLE_ADMIN");
        assertThatThrownBy(() -> CurrentUser.id(otra))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(CurrentUser.role(otra)).isNull();
        assertThat(CurrentUser.companyId(otra)).isNull();
        assertThat(CurrentUser.hasRole(null, "ADMIN")).isFalse();
        assertThat(CurrentUser.companyId(token("OPERADOR", null))).isNull();
    }

    @Test
    void alcanceDeEmpresa() {
        assertThat(CurrentUser.companyScope(token("ADMIN", EMPRESA))).isNull();
        assertThat(CurrentUser.companyScope(token("OPERADOR", EMPRESA))).isEqualTo(EMPRESA);
        assertThatThrownBy(() -> CurrentUser.companyScope(token("OPERADOR", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("FORBIDDEN");
    }

    @Test
    void elConvertidorDeRolesUsaElClaimRole() {
        JwtRoleConverter converter = new JwtRoleConverter();
        assertThat(converter.convert(jwt("ADMIN", null)).getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(converter.convert(jwt(null, null)).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt("ADMIN", null)).getName()).isEqualTo(USUARIO.toString());
    }

    private static Authentication token(String rol, UUID empresa) {
        return new JwtRoleConverter().convert(jwt(rol, empresa));
    }

    private static Jwt jwt(String rol, UUID empresa) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject(USUARIO.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (rol != null) {
            b.claim("role", rol);
        }
        if (empresa != null) {
            b.claim("companyId", empresa.toString());
        }
        return b.build();
    }
}
