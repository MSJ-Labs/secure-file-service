package com.msj.securefile.storage.application.result;

import java.io.InputStream;

/**
 * The content is an open stream: whoever receives this result must close it.
 */
public record FileDownload(String name, long size, InputStream content) {
}