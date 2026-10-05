package com.msj.securefile.storage.application.command.recordverdict;

/**
 * What the scanner concluded. Two records rather than a flag and a nullable signature: an infected verdict always
 * carries its signature, a clean one never does.
 */
public sealed interface ScanVerdict {

    record Clean() implements ScanVerdict {
    }

    record Infected(String signature) implements ScanVerdict {
    }
}