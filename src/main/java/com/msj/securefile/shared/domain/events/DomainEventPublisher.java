package com.msj.securefile.shared.domain.events;

import com.msj.securefile.shared.domain.DomainEvent;

import java.util.List;

public interface DomainEventPublisher {
    void publish(List<DomainEvent> events);
}