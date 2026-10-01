package com.msj.securefile.shared.domain;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredOn();
}