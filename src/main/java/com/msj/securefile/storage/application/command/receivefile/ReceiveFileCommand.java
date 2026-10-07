package com.msj.securefile.storage.application.command.receivefile;

import java.io.InputStream;

/**
 * The content is the stream of the request: the handler reads it once, never more than the declared size.
 */
public record ReceiveFileCommand(String name, long declaredSize, InputStream content) {
}
