package com.msj.securefile.storage.api;

import com.msj.securefile.storage.domain.file.valueobject.FileId;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets a controller take a {@link FileId} straight from the URL. A text that is not an id makes the conversion fail,
 * which {@link StorageExceptionHandler} answers as an unknown file.
 */
@Component
class FileIdConverter implements Converter<String, FileId> {

    @Override
    public FileId convert(String source) {
        return FileId.parse(source);
    }
}
