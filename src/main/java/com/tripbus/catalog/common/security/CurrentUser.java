package com.tripbus.catalog.common.security;

import com.tripbus.catalog.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/** Utilidades para leer el usuario autenticado (claims del JWT emitido por identity). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return UUID.fromString(token.getToken().getSubject());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Se requiere un usuario autenticado");
    }

    public static String role(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken().getClaimAsString("role");
        }
        return null;
    }

    public static UUID companyId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String companyId = token.getToken().getClaimAsString("companyId");
            return companyId != null ? UUID.fromString(companyId) : null;
        }
        return null;
    }

    /**
     * Empresa a la que se limita la petición: null para el ADMIN (todas), la del claim companyId para el
     * OPERADOR. Un operador sin empresa en el token no puede gestionar nada (403).
     */
    public static UUID companyScope(Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return null;
        }
        UUID companyId = companyId(authentication);
        if (companyId == null) {
            throw ApiException.forbidden("FORBIDDEN", "El operador no tiene una empresa asignada");
        }
        return companyId;
    }

    public static boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}
