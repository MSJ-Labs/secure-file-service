package com.msj.securefile.auth.infrastructure.adapters.persistence;

import com.msj.securefile.auth.application.port.out.RefreshTokenRepository;
import com.msj.securefile.auth.domain.user.UserId;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.auth.Tables.REFRESH_TOKEN;

@Component
@RequiredArgsConstructor
public class JooqRefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Override
    @Transactional
    public void save(String tokenHash, UserId userId, LocalDateTime expiresAt) {
        dsl.insertInto(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
                .set(REFRESH_TOKEN.USER_ID, userId.value().toLong())
                .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
                .set(REFRESH_TOKEN.REVOKED, false)
                .set(REFRESH_TOKEN.CREATED_AT, LocalDateTime.now(clock))
                .execute();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValid(String tokenHash) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(REFRESH_TOKEN)
                        .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
                        .and(REFRESH_TOKEN.REVOKED.eq(false))
                        .and(REFRESH_TOKEN.EXPIRES_AT.gt(LocalDateTime.now(clock)))
        );
    }

    @Override
    @Transactional
    public void revoke(String tokenHash) {
        dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED, true)
                .set(REFRESH_TOKEN.REVOKED_AT, LocalDateTime.now(clock))
                .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
                .execute();
    }

    @Override
    @Transactional
    public void revokeAllByUserId(UserId userId) {
        dsl.update(REFRESH_TOKEN)
                .set(REFRESH_TOKEN.REVOKED, true)
                .set(REFRESH_TOKEN.REVOKED_AT, LocalDateTime.now(clock))
                .where(REFRESH_TOKEN.USER_ID.eq(userId.value().toLong()))
                .and(REFRESH_TOKEN.REVOKED.eq(false))
                .execute();
    }
}