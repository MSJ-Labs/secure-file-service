package com.msj.securefile.auth.domain.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void hash_returns_64_char_hex_string() {
        assertThat(TokenHasher.hash("any-token")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void same_input_always_produces_same_hash() {
        assertThat(TokenHasher.hash("token")).isEqualTo(TokenHasher.hash("token"));
    }

    @Test
    void different_inputs_produce_different_hashes() {
        assertThat(TokenHasher.hash("token-a")).isNotEqualTo(TokenHasher.hash("token-b"));
    }
}
