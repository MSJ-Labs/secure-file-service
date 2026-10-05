package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {

    @Test
    void user_isIdentifiedByItsOwnerId() {
        assertThat(new Actor.User(OwnerId.of(7L))).isEqualTo(new Actor.User(OwnerId.of(7L)));
    }

    @Test
    void user_refusesANullOwner() {
        assertThatThrownBy(() -> new Actor.User(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void worker_isIdentifiedByItsWorkerId() {
        assertThat(new Actor.Worker(WorkerId.of("worker-1"))).isEqualTo(new Actor.Worker(WorkerId.of("worker-1")));
    }

    @Test
    void worker_refusesANullWorker() {
        assertThatThrownBy(() -> new Actor.Worker(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void system_hasNoIdentity() {
        // The reapers act for nobody: two system actors are the same actor.
        assertThat(new Actor.System()).isEqualTo(new Actor.System());
    }
}
