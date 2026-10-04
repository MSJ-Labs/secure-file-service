package com.msj.securefile.storage.domain.file.valueobject;

import com.msj.securefile.shared.domain.ValueObject;

import java.util.regex.Pattern;

/**
 * SHA-256 digest as 64 lowercase hex characters, the only form the database accepts.
 */
public record Sha256(String value) implements ValueObject {

    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        if (value == null || !HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("A SHA-256 digest is 64 lowercase hex characters");
        }
    }

    public static Sha256 of(String value) {
        return new Sha256(value);
    }
}