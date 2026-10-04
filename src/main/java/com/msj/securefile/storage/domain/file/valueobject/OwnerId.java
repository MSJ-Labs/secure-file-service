package com.msj.securefile.storage.domain.file.valueobject;

import com.msj.securefile.shared.domain.ValueObject;
import io.hypersistence.tsid.TSID;

/**
 * The caller who owns a file. The storage context has its own type on purpose: it does not depend on the auth
 * context, an adapter translates the authenticated user into an OwnerId.
 */
public record OwnerId(TSID value) implements ValueObject {

    public OwnerId {
        if (value == null) throw new IllegalArgumentException("OwnerId cannot be null");
    }

    public static OwnerId of(long value) {
        return new OwnerId(TSID.from(value));
    }
}