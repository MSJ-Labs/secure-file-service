package com.msj.securefile.shared.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityTest {

    private static class Thing extends Entity<Long> {
        Thing(Long id) {
            super(id);
        }
    }

    private static class OtherThing extends Entity<Long> {
        OtherThing(Long id) {
            super(id);
        }
    }

    @Test
    void id_returnsTheIdentity() {
        assertThat(new Thing(42L).id()).isEqualTo(42L);
    }

    @Test
    void entitiesWithTheSameIdAreEqual() {
        assertThat(new Thing(1L))
                .isEqualTo(new Thing(1L))
                .hasSameHashCodeAs(new Thing(1L));
    }

    @Test
    void entitiesWithDifferentIdsAreNotEqual() {
        assertThat(new Thing(1L)).isNotEqualTo(new Thing(2L));
    }

    @Test
    void entitiesOfDifferentTypesAreNotEqualEvenWithTheSameId() {
        assertThat(new Thing(1L)).isNotEqualTo(new OtherThing(1L));
    }

    @Test
    void entityIsNotEqualToNullOrToAnotherKindOfObject() {
        Thing thing = new Thing(1L);

        assertThat(thing).isNotEqualTo(null).isNotEqualTo("1");
    }

    @Test
    void entitiesWithoutIdAreEqualOnlyToThemselvesOrToOtherUnidentifiedOnes() {
        Thing unidentified = new Thing(null);

        assertThat(unidentified).isEqualTo(unidentified);
        assertThat(unidentified.hashCode()).isZero();
        assertThat(unidentified).isNotEqualTo(new Thing(1L));
    }
}
