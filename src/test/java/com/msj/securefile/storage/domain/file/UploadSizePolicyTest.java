package com.msj.securefile.storage.domain.file;

import com.msj.securefile.storage.domain.file.exception.FileTooLargeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadSizePolicyTest {

    private static final long MAX = 2_147_483_648L;

    private final UploadSizePolicy policy = new UploadSizePolicy(MAX);

    @Test
    void ensureAllowed_acceptsASizeUpToTheMaximum() {
        assertThatCode(() -> policy.ensureAllowed(0)).doesNotThrowAnyException();
        assertThatCode(() -> policy.ensureAllowed(MAX)).doesNotThrowAnyException();
    }

    @Test
    void ensureAllowed_refusesASizeAboveTheMaximum() {
        assertThatThrownBy(() -> policy.ensureAllowed(MAX + 1)).isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void constructor_refusesAMaximumThatIsNotPositive() {
        assertThatThrownBy(() -> new UploadSizePolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadSizePolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
