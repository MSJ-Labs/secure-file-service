package com.msj.securefile.storage.infrastructure.adapters.security;

import com.msj.securefile.shared.infrastructure.security.CurrentAuthenticatedPrincipal;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityContextCurrentUserProviderTest {

    private static final UUID USER_ID = UUID.fromString("3f1c9a52-0000-4000-8000-000000000001");

    private final SecurityContextCurrentUserProvider provider =
            new SecurityContextCurrentUserProvider(new CurrentAuthenticatedPrincipal());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentOwner_isTheTokenSubjectTranslatedToAnOwnerId() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(provider.currentOwner()).isEqualTo(OwnerId.of(USER_ID));
    }

    @Test
    void currentOwner_failsWhenNobodyIsAuthenticated() {
        assertThatThrownBy(provider::currentOwner).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void currentOwner_failsClosedForTheAnonymousUser() {
        // It must never be mistaken for an owner.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(provider::currentOwner).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
