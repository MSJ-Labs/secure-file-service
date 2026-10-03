package com.msj.securefile.auth.infrastructure.adapters.persistence;

import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.msj.securefile.shared.infrastructure.persistence.jooq.auth.Tables.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class JooqUserRepositoryAdapter implements UserRepository {

    private final DSLContext dsl;
    private final Clock clock;

    @Override
    @Transactional
    public User save(User user) {
        long id = user.getId().value().toLong();

        dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, user.getUsername())
                .set(USER_ACCOUNT.EMAIL, user.getEmail())
                .set(USER_ACCOUNT.PASSWORD, user.getPasswordHash())
                .set(USER_ACCOUNT.FIRST_NAME, user.getFirstName())
                .set(USER_ACCOUNT.LAST_NAME, user.getLastName())
                .set(USER_ACCOUNT.ENABLED, user.isEnabled())
                .set(USER_ACCOUNT.ACCOUNT_NON_EXPIRED, user.isAccountNonExpired())
                .set(USER_ACCOUNT.ACCOUNT_NON_LOCKED, user.isAccountNonLocked())
                .set(USER_ACCOUNT.CREDENTIALS_NON_EXPIRED, user.isCredentialsNonExpired())
                .set(USER_ACCOUNT.CREATED_AT, user.getCreatedAt())
                .set(USER_ACCOUNT.UPDATED_AT, user.getUpdatedAt())
                .set(USER_ACCOUNT.LAST_LOGIN_AT, user.getLastLoginAt())
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, user.getFailedLoginAttempts())
                .set(USER_ACCOUNT.LOCKED_UNTIL, user.getLockedUntil())
                .onConflict(USER_ACCOUNT.ID)
                .doUpdate()
                .set(USER_ACCOUNT.EMAIL, user.getEmail())
                .set(USER_ACCOUNT.PASSWORD, user.getPasswordHash())
                .set(USER_ACCOUNT.FIRST_NAME, user.getFirstName())
                .set(USER_ACCOUNT.LAST_NAME, user.getLastName())
                .set(USER_ACCOUNT.ENABLED, user.isEnabled())
                .set(USER_ACCOUNT.ACCOUNT_NON_EXPIRED, user.isAccountNonExpired())
                .set(USER_ACCOUNT.ACCOUNT_NON_LOCKED, user.isAccountNonLocked())
                .set(USER_ACCOUNT.CREDENTIALS_NON_EXPIRED, user.isCredentialsNonExpired())
                .set(USER_ACCOUNT.UPDATED_AT, user.getUpdatedAt())
                .set(USER_ACCOUNT.LAST_LOGIN_AT, user.getLastLoginAt())
                .set(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS, user.getFailedLoginAttempts())
                .set(USER_ACCOUNT.LOCKED_UNTIL, user.getLockedUntil())
                .execute();

        syncRoles(id, user.getRoles() == null ? Set.of() : user.getRoles());

        return user;
    }

    // The user's role set is the source of truth: add the missing links, drop the ones that are gone.
    private void syncRoles(long userId, Set<String> roleNames) {
        Map<String, Long> roleIds = dsl.select(ROLE.NAME, ROLE.ID)
                .from(ROLE)
                .where(ROLE.NAME.in(roleNames))
                .fetchMap(ROLE.NAME, ROLE.ID);
        if (roleIds.size() != roleNames.size()) {
            throw new IllegalArgumentException("Unknown role in " + roleNames);
        }

        dsl.deleteFrom(USER_ROLE)
                .where(USER_ROLE.USER_ID.eq(userId))
                .and(USER_ROLE.ROLE_ID.notIn(roleIds.values()))
                .execute();

        LocalDateTime now = LocalDateTime.now(clock);
        for (Long roleId : roleIds.values()) {
            dsl.insertInto(USER_ROLE)
                    .set(USER_ROLE.USER_ID, userId)
                    .set(USER_ROLE.ROLE_ID, roleId)
                    .set(USER_ROLE.ASSIGNED_AT, now)
                    .onConflict(USER_ROLE.USER_ID, USER_ROLE.ROLE_ID)
                    .doNothing()
                    .execute();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        Record row = dsl.select()
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.USERNAME.eq(username))
                .fetchOne();

        return Optional.ofNullable(row).map(r -> mapWithRoles(r, UserId.of(r.get(USER_ACCOUNT.ID))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        Record row = dsl.select()
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.EMAIL.eq(email))
                .fetchOne();

        return Optional.ofNullable(row).map(r -> mapWithRoles(r, UserId.of(r.get(USER_ACCOUNT.ID))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UserId id) {
        Record row = dsl.select()
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(id.value().toLong()))
                .fetchOne();

        return Optional.ofNullable(row).map(r -> mapWithRoles(r, id));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return dsl.fetchExists(dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.USERNAME.eq(username)));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return dsl.fetchExists(dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(email)));
    }

    // Single query — user + roles joined, no N+1
    private User mapWithRoles(Record row, UserId userId) {
        Set<String> roles = dsl.select(ROLE.NAME)
                .from(USER_ROLE)
                .join(ROLE).on(ROLE.ID.eq(USER_ROLE.ROLE_ID))
                .where(USER_ROLE.USER_ID.eq(userId.value().toLong()))
                .fetchSet(ROLE.NAME);

        return User.reconstitute(
                userId,
                row.get(USER_ACCOUNT.USERNAME),
                row.get(USER_ACCOUNT.EMAIL),
                row.get(USER_ACCOUNT.PASSWORD),
                row.get(USER_ACCOUNT.FIRST_NAME),
                row.get(USER_ACCOUNT.LAST_NAME),
                row.get(USER_ACCOUNT.ENABLED),
                row.get(USER_ACCOUNT.ACCOUNT_NON_EXPIRED),
                row.get(USER_ACCOUNT.ACCOUNT_NON_LOCKED),
                row.get(USER_ACCOUNT.CREDENTIALS_NON_EXPIRED),
                row.get(USER_ACCOUNT.CREATED_AT),
                row.get(USER_ACCOUNT.UPDATED_AT),
                row.get(USER_ACCOUNT.LAST_LOGIN_AT),
                row.get(USER_ACCOUNT.FAILED_LOGIN_ATTEMPTS),
                row.get(USER_ACCOUNT.LOCKED_UNTIL),
                roles);
    }
}