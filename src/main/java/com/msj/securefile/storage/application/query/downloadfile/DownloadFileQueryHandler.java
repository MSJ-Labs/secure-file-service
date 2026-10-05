package com.msj.securefile.storage.application.query.downloadfile;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.application.port.out.StorageZone;
import com.msj.securefile.storage.application.result.FileDownload;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.exception.SecureFileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Not transactional on purpose: the lookup is a short read, then the content streams with no database connection held.
 */
@Service
@RequiredArgsConstructor
public class DownloadFileQueryHandler {

    private final FileRepository fileRepository;
    private final CurrentUserProvider currentUserProvider;
    private final FileStoragePort fileStoragePort;

    public FileDownload handle(DownloadFileQuery query) {
        SecureFile file = fileRepository.findByIdAndOwner(query.fileId(), currentUserProvider.currentOwner())
                .orElseThrow(SecureFileNotFoundException::new);
        file.ensureDownloadable();

        return new FileDownload(file.getName(), file.getDeclaredSize(),
                fileStoragePort.open(StorageZone.CLEAN, file.id()));
    }
}