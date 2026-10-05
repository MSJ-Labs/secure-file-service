package com.msj.securefile.storage.infrastructure.adapters.security;

import com.msj.securefile.shared.infrastructure.security.AuthenticatedPrincipal;
import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Translates the authenticated principal into the storage context's own OwnerId. It fails closed: anything that cannot
 * say who the caller is, the anonymous user included, is refused rather than guessed.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public OwnerId currentOwner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal) {
            return OwnerId.of(principal.id());
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated user.");
    }
}