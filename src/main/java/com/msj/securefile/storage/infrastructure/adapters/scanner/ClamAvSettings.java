package com.msj.securefile.storage.infrastructure.adapters.scanner;

import java.time.Duration;

/**
 * Where clamd listens and how long to wait for it. The read timeout bounds a whole scan of a large file, so it is long;
 * the connect timeout only has to tell a clamd that is down from one that is slow.
 */
public record ClamAvSettings(String host, int port, Duration connectTimeout, Duration readTimeout) {

    public ClamAvSettings {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("The ClamAV host is required");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("The ClamAV port must be a valid port");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("The connect timeout must be positive");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("The read timeout must be positive");
        }
    }
}
