package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.valueobject.OwnerId;
import com.msj.securefile.storage.domain.scan.valueobject.WorkerId;

/**
 * Who a change is made by, recorded with every event of the audit trail. The handler that makes the change says it:
 * the caller, the scan worker, or the system itself when a reaper acts for nobody.
 */
public sealed interface Actor {

    record User(OwnerId owner) implements Actor {

        public User {
            if (owner == null) throw new IllegalArgumentException("Owner is required");
        }
    }

    record Worker(WorkerId worker) implements Actor {

        public Worker {
            if (worker == null) throw new IllegalArgumentException("Worker is required");
        }
    }

    record System() implements Actor {
    }
}
