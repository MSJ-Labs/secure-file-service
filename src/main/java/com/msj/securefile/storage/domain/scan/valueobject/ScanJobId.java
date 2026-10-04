package com.msj.securefile.storage.domain.scan.valueobject;

import com.msj.securefile.shared.domain.ValueObject;
import io.hypersistence.tsid.TSID;

public record ScanJobId(TSID value) implements ValueObject {

    public ScanJobId {
        if (value == null) throw new IllegalArgumentException("ScanJobId cannot be null");
    }

    public static ScanJobId of(long value) {
        return new ScanJobId(TSID.from(value));
    }
}