package com.msj.securefile.shared.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateRootTest {

    private record SomethingHappened(Instant occurredOn) implements DomainEvent {
    }

    private static class Order extends AggregateRoot<Long> {
        Order(Long id) {
            super(id);
        }

        void happen(DomainEvent event) {
            registerEvent(event);
        }
    }

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void newAggregateHasNoEvents() {
        assertThat(new Order(1L).pullDomainEvents()).isEmpty();
    }

    @Test
    void pullDomainEvents_returnsTheRegisteredEventsInOrder() {
        Order order = new Order(1L);
        DomainEvent first = new SomethingHappened(NOW);
        DomainEvent second = new SomethingHappened(NOW.plusSeconds(1));
        order.happen(first);
        order.happen(second);

        assertThat(order.pullDomainEvents()).containsExactly(first, second);
    }

    @Test
    void pullDomainEvents_clearsTheEventsSoTheyArePublishedOnce() {
        Order order = new Order(1L);
        order.happen(new SomethingHappened(NOW));
        order.pullDomainEvents();

        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void pullDomainEvents_returnsASnapshotThatCannotBeModified() {
        Order order = new Order(1L);
        order.happen(new SomethingHappened(NOW));
        List<DomainEvent> events = order.pullDomainEvents();

        assertThatThrownBy(() -> events.add(new SomethingHappened(NOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aggregateIsAnEntityIdentifiedByItsId() {
        assertThat(new Order(1L)).isEqualTo(new Order(1L)).isNotEqualTo(new Order(2L));
    }
}
