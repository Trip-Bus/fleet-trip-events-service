package com.tripbus.fleet.common.security;

import com.tripbus.fleet.common.error.ApiException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Lectura del usuario del JWT (rol, empresa) y autenticación servicio a servicio (/internal/**). */
class SecurityUnitTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private Jwt jwt(String role, UUID company) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (role != null) {
            builder.claim("role", role);
        }
        if (company != null) {
            builder.claim("companyId", company.toString());
        }
        return builder.build();
    }

    @Test
    void elRolDelJwtSeConvierteEnAutoridad() {
        Authentication auth = new JwtRoleConverter().convert(jwt("CONDUCTOR", companyId));

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CONDUCTOR");
        assertThat(CurrentUser.id(auth)).isEqualTo(userId);
        assertThat(CurrentUser.role(auth)).isEqualTo("CONDUCTOR");
        assertThat(CurrentUser.companyId(auth)).isEqualTo(companyId);
        assertThat(CurrentUser.hasRole(auth, "CONDUCTOR")).isTrue();
        assertThat(CurrentUser.hasRole(auth, "ADMIN")).isFalse();
    }

    @Test
    void sinRolNiEmpresa() {
        Authentication auth = new JwtRoleConverter().convert(jwt(null, null));

        assertThat(auth.getAuthorities()).isEmpty();
        assertThat(CurrentUser.companyId(auth)).isNull();
        assertThat(CurrentUser.hasRole(null, "ADMIN")).isFalse();
    }

    @Test
    void sinJwtNoHayUsuario() {
        Authentication otra = new TestingAuthenticationToken("x", null);

        assertThat(CurrentUser.role(otra)).isNull();
        assertThat(CurrentUser.companyId(otra)).isNull();
        assertThatThrownBy(() -> CurrentUser.id(otra)).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "UNAUTHENTICATED");
    }

    @Test
    void elTokenInternoSoloAutenticaRutasInternas() throws Exception {
        InternalTokenFilter filtro = new InternalTokenFilter("secreto");

        MockHttpServletRequest interna = new MockHttpServletRequest("GET", "/internal/algo");
        interna.addHeader(InternalTokenFilter.HEADER, "secreto");
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(interna, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.eq(interna), org.mockito.ArgumentMatchers.any());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_INTERNAL");
        SecurityContextHolder.clearContext();

        // Token equivocado o ruta pública: no autentica.
        MockHttpServletRequest malToken = new MockHttpServletRequest("GET", "/internal/algo");
        malToken.addHeader(InternalTokenFilter.HEADER, "otro");
        filtro.doFilter(malToken, new MockHttpServletResponse(), mock(FilterChain.class));
        MockHttpServletRequest publica = new MockHttpServletRequest("GET", "/trips/1/timeline");
        publica.addHeader(InternalTokenFilter.HEADER, "secreto");
        filtro.doFilter(publica, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
