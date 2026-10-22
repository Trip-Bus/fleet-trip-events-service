package com.tripbus.fleet.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/** Cada excepción se traduce al formato {"error":{"code","message","details"}} con su status. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void esperar(ResponseEntity<ErrorResponse> respuesta, HttpStatus status, String code) {
        assertThat(respuesta.getStatusCode()).isEqualTo(status);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().error().code()).isEqualTo(code);
    }

    @Test
    void traduceLasExcepcionesAlFormatoDeError() {
        esperar(handler.handleApi(ApiException.conflict("X", "choque")), HttpStatus.CONFLICT, "X");
        esperar(handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PATCH")),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        esperar(handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "/nada")),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        esperar(handler.handleAccessDenied(new AccessDeniedException("no")), HttpStatus.FORBIDDEN, "FORBIDDEN");
        esperar(handler.handleAuthentication(new BadCredentialsException("no")), HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED");
        esperar(handler.handleUnexpected(new IllegalStateException("boom")), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR");
    }
}
