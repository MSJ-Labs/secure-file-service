package com.msj.securefile.storage.application.query.listfiles;

import com.msj.securefile.storage.application.port.out.CurrentUserProvider;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.result.FileSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The files of the caller only: the owner comes from the authenticated principal, never from a parameter.
 */
@Service
@RequiredArgsConstructor
public class ListMyFilesQueryHandler {

    private final FileRepository fileRepository;
    private final CurrentUserProvider currentUserProvider;

    public List<FileSummary> handle() {
        return fileRepository.findAllByOwner(currentUserProvider.currentOwner()).stream()
                .map(file -> new FileSummary(file.id().asString(), file.getName(), file.getDeclaredSize(),
                        file.getStatus(), file.getCreatedAt()))
                .toList();
    }
}
