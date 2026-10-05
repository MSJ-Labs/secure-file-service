package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.domain.file.valueobject.Sha256;

/**
 * What the storage measured while it streamed the body: the server's own size and digest, never the client's.
 */
public record StoredContent(long size, Sha256 digest) {
}