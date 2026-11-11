package com.msj.securefile.storage.domain.file.valueobject;

import com.msj.securefile.shared.domain.ValueObject;
import java.util.UUID;

/**
 * The caller who owns a file: the Keycloak subject. The storage context has its own type on purpose, an adapter
 * translates the authenticated user into an OwnerId.
 */
public record OwnerId(UUID value) implements ValueObject {

    public OwnerId {
        if (value == null) throw new IllegalArgumentException("OwnerId cannot be null");
    }

    public static OwnerId of(UUID value) {
        return new OwnerId(value);
    }
}