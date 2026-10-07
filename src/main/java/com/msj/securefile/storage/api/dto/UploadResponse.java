package com.msj.securefile.storage.api.dto;

/**
 * The status is always PENDING: the upload handler only returns once the content is stored and the scan is queued.
 */
public record UploadResponse(String id, String name, long size, String status) {
}
