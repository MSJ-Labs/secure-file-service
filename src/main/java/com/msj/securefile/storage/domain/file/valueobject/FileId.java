package com.msj.securefile.storage.domain.file.valueobject;

import com.msj.securefile.shared.domain.ValueObject;
import io.hypersistence.tsid.TSID;

public record FileId(TSID value) implements ValueObject {

    public FileId {
        if (value == null) throw new IllegalArgumentException("FileId cannot be null");
    }

    public static FileId of(long value) {
        return new FileId(TSID.from(value));
    }

    // The inverse of asString: throws IllegalArgumentException for a text that is not a TSID.
    public static FileId parse(String text) {
        return new FileId(TSID.from(text));
    }

    // Lets the outer layers print the id without touching the TSID type.
    public String asString() {
        return value.toString();
    }
}