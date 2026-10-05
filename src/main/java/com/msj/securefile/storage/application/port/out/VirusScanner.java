package com.msj.securefile.storage.application.port.out;

import com.msj.securefile.storage.application.command.recordverdict.ScanVerdict;

import java.io.InputStream;

/**
 * Output port to the antivirus. The content is streamed to it, never loaded in memory. Throws
 * ScannerUnavailableException when it cannot be reached and ScanExecutionException when the scan does not complete.
 */
public interface VirusScanner {

    ScanVerdict scan(InputStream content);
}