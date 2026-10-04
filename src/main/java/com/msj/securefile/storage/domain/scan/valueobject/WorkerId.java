package com.msj.securefile.storage.domain.scan.valueobject;

import com.msj.securefile.shared.domain.ValueObject;

/**
 * Names the worker that holds a lease. It is compared on every heartbeat and on completion to know who still owns
 * the job, so two workers must never share a name.
 */
public record WorkerId(String value) implements ValueObject {

    // Matches the VARCHAR(128) lease_owner column.
    private static final int MAX_LENGTH = 128;

    public WorkerId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Worker id is required");
        value = value.trim();
        if (value.length() > MAX_LENGTH) throw new IllegalArgumentException("Worker id is too long");
    }

    public static WorkerId of(String value) {
        return new WorkerId(value);
    }
}