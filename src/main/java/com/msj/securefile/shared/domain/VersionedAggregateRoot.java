package com.msj.securefile.shared.domain;

/**
 * An aggregate whose changes are guarded by a version (compare-and-set) and whose events are ordered by it. Kept apart
 * from AggregateRoot so the aggregates that do not need it, such as User, do not carry a meaningless version.
 */
public abstract class VersionedAggregateRoot<ID> extends AggregateRoot<ID> {

    // The version the aggregate was loaded with, 0 when it is new. The version to store is this one plus the number
    // of events pulled: computed by the adapter, never kept here.
    private final long version;

    protected VersionedAggregateRoot(ID id) {
        this(id, 0L);
    }

    protected VersionedAggregateRoot(ID id, long loadedVersion) {
        super(id);
        if (loadedVersion < 0) throw new IllegalArgumentException("Version cannot be negative");
        this.version = loadedVersion;
    }

    public long version() {
        return version;
    }
}