package com.msj.securefile.storage.infrastructure.adapters.id;

import com.msj.securefile.storage.application.port.out.IdGenerator;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.storage.domain.scan.valueobject.ScanJobId;
import io.hypersistence.tsid.TSID;
import org.springframework.stereotype.Component;

/**
 * Ids are TSIDs generated here, never by the database: time-ordered, so they index well, and unique without a sequence.
 */
@Component
public class TsidIdGenerator implements IdGenerator {

    @Override
    public FileId nextFileId() {
        return new FileId(TSID.fast());
    }

    @Override
    public ScanJobId nextScanJobId() {
        return new ScanJobId(TSID.fast());
    }
}