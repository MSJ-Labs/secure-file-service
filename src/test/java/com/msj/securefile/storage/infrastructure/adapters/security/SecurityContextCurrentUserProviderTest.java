package com.msj.securefile.storage.infrastructure.adapters.security;

import com.msj.securefile.shared.infrastructure.security.AuthenticatedPrincipal;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityContextCurrentUserProviderTest {

    private record TestPrincipal(long id) implements AuthenticatedPrincipal {
    }

    private final SecurityContextCurrentUserProvider provider = new SecurityContextCurrentUserProvider();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Object principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void currentOwner_isTheAuthenticatedPrincipalTranslatedToAnOwnerId() {
        authenticateAs(new TestPrincipal(42L));

        assertThat(provider.currentOwner()).isEqualTo(OwnerId.of(42L));
    }

    @Test
    void currentOwner_failsWhenNobodyIsAuthenticated() {
        assertThatThrownBy(provider::currentOwner).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void currentOwner_failsClosedForAPrincipalThatCannotTellItsId() {
        // For instance the anonymous user: it must never be mistaken for an owner.
        authenticateAs("anonymousUser");

        assertThatThrownBy(provider::currentOwner).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}