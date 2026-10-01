package com.msj.securefile.shared.domain;

import java.util.ArrayList;
import java.util.List;

public abstract class AggregateRoot<ID> extends Entity<ID> {

    private final List<DomainEvent> domainEvents;

    protected AggregateRoot(ID id) {
        super(id);
        this.domainEvents = new ArrayList<>();
    }

    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> snapshot = List.copyOf(domainEvents);
        domainEvents.clear();
        return snapshot;
    }
}