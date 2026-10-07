package com.msj.securefile.storage.api;

import com.msj.securefile.storage.api.dto.FileResponse;
import com.msj.securefile.storage.api.dto.UploadResponse;
import com.msj.securefile.storage.application.command.receivefile.ReceiveFileCommand;
import com.msj.securefile.storage.application.command.receivefile.ReceiveFileCommandHandler;
import com.msj.securefile.storage.application.command.receivefile.ReceivedFile;
import com.msj.securefile.storage.application.query.downloadfile.DownloadFileQuery;
import com.msj.securefile.storage.application.query.downloadfile.DownloadFileQueryHandler;
import com.msj.securefile.storage.application.query.listfiles.ListMyFilesQueryHandler;
import com.msj.securefile.storage.application.result.FileDownload;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The web side of the files: it reads what HTTP gives (the body, the size from Content-Length) and calls one handler
 * per request. The rules (the size limit, the ownership, the states) belong to the handlers and the domain. Identity
 * comes from the authenticated principal inside them; an id in the URL never grants access by itself.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final ReceiveFileCommandHandler receiveFile;
    private final ListMyFilesQueryHandler listMyFiles;
    private final DownloadFileQueryHandler downloadFile;

    // The size is a required header and the body is the raw stream: a request without Content-Length is refused by
    // Spring before this method runs (StorageExceptionHandler answers 411).
    @PutMapping
    public ResponseEntity<UploadResponse> upload(@RequestParam String name,
                                                 @RequestHeader(HttpHeaders.CONTENT_LENGTH) long size,
                                                 InputStream body) {
        ReceivedFile received = receiveFile.handle(new ReceiveFileCommand(name, size, body));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponse(received.fileId(), name, size, "PENDING"));
    }

    @GetMapping
    public List<FileResponse> list() {
        return listMyFiles.handle().stream().map(FileResponse::from).toList();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable FileId id) {
        FileDownload download = downloadFile.handle(new DownloadFileQuery(id));

        // Always an attachment of an opaque type, so the browser never renders the content as a page.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.name(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(download.content()));
    }
}
