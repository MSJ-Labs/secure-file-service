package com.msj.securefile.storage.infrastructure.adapters.persistence.audit;

import java.time.Instant;
import java.util.Map;

/**
 * An event of the domain, ready to be written to the audit table: the envelope (which aggregate, which file, when) and
 * the data of the event itself. The version and the actor are added at write time, they are not part of the fact.
 */
public record AuditEvent(AggregateType aggregateType, long aggregateId, long fileId, String eventType,
                         Instant occurredAt, Map<String, Object> payload) {

    public enum AggregateType {
        FILE,
        SCAN_JOB
    }
}
