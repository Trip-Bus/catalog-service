package com.tripbus.catalog.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Cada excepción se traduce al formato {"error":{"code","message","details"}} con su status. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void erroresDeNegocioYValidacion() {
        verificar(handler.handleApi(ApiException.conflict("X", "y")), HttpStatus.CONFLICT, "X");
        verificar(handler.handleMethodValidation(mock(HandlerMethodValidationException.class)),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        verificar(handler.handleBadRequest(new MissingServletRequestParameterException("date", "LocalDate")),
                HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        verificar(handler.handleDataIntegrity(new DataIntegrityViolationException("dup")),
                HttpStatus.CONFLICT, "CONFLICT");
    }

    @Test
    void erroresDeHttpYSeguridad() {
        verificar(handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PATCH")),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        verificar(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/nada")),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        verificar(handler.handleAccessDenied(new AccessDeniedException("no")), HttpStatus.FORBIDDEN, "FORBIDDEN");
        verificar(handler.handleAuthentication(new BadCredentialsException("no")),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void errorInesperadoNoExponeElDetalle() {
        ResponseEntity<ErrorResponse> r = handler.handleUnexpected(new IllegalStateException("secreto"));
        verificar(r, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(r.getBody().error().message()).doesNotContain("secreto");
    }

    private static void verificar(ResponseEntity<ErrorResponse> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).isEqualTo(status);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().error().code()).isEqualTo(code);
    }
}
