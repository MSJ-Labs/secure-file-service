package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.valueobject.OwnerId;

/**
 * The caller of the current use case. Implemented by an adapter that translates the authenticated principal into
 * the storage context's own OwnerId.
 */
public interface CurrentUserProvider {

    OwnerId currentOwner();
}