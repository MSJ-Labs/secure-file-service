package com.msj.securefile.storage.infrastructure.adapters.security;

import com.msj.securefile.shared.infrastructure.security.CurrentAuthenticatedPrincipal;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Translates the authenticated principal into the storage context's own OwnerId. It fails closed: anything that cannot
 * say who the caller is, the anonymous user included, is refused rather than guessed.
 */
@Component
@RequiredArgsConstructor
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    private final CurrentAuthenticatedPrincipal currentPrincipal;

    @Override
    public OwnerId currentOwner() {
        return currentPrincipal.get()
                .map(principal -> OwnerId.of(principal.userId()))
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user."));
    }
}
