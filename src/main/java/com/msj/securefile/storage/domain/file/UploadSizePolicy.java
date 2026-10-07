package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.exception.FileTooLargeException;

/**
 * The largest file the service accepts. Checked from the declared size when an upload starts, so a refused file costs
 * neither a row nor a byte of its body. The limit is configuration, not a constant of the domain.
 */
public record UploadSizePolicy(long maxBytes) {

    public UploadSizePolicy {
        if (maxBytes <= 0) throw new IllegalArgumentException("The maximum file size must be positive");
    }

    public void ensureAllowed(long declaredSize) {
        if (declaredSize > maxBytes) throw new FileTooLargeException();
    }
}
