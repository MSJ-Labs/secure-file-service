package com.msj.securefile.shared.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedAggregateRootTest {

    private record SomethingHappened(Instant occurredOn) implements DomainEvent {
    }

    private static class Order extends VersionedAggregateRoot<Long> {
        Order(Long id) {
            super(id);
        }

        Order(Long id, long loadedVersion) {
            super(id, loadedVersion);
        }

        void happen(DomainEvent event) {
            registerEvent(event);
        }
    }

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void version_isZeroForANewAggregate() {
        assertThat(new Order(1L).version()).isZero();
    }

    @Test
    void version_isTheOneTheAggregateWasLoadedWith() {
        assertThat(new Order(1L, 7L).version()).isEqualTo(7L);
    }

    @Test
    void version_staysTheLoadedOneWhenEventsAreRegistered() {
        // The new version is loaded version plus the number of events: the adapter computes it, nothing is stored here.
        Order order = new Order(1L, 7L);
        order.happen(new SomethingHappened(NOW));

        assertThat(order.version()).isEqualTo(7L);
    }

    @Test
    void constructor_refusesANegativeVersion() {
        assertThatThrownBy(() -> new Order(1L, -1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stillCollectsItsEventsLikeAnyAggregateRoot() {
        Order order = new Order(1L, 7L);
        DomainEvent event = new SomethingHappened(NOW);
        order.happen(event);

        assertThat(order.pullDomainEvents()).containsExactly(event);
    }
}