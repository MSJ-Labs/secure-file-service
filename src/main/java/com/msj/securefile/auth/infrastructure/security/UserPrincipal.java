package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;
import com.msj.securefile.shared.infrastructure.security.AuthenticatedPrincipal;
import lombok.Getter;
import org.springframework.lang.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class UserPrincipal implements UserDetails, AuthenticatedPrincipal {

    @Getter @Nullable
    private final transient User user;
    // Kept as the TSID's long so the principal stays serializable (UserId is not).
    private final long userId;
    private final String username;
    private final Set<GrantedAuthority> authorities;

    // Full user loaded from DB (registration, profile queries)
    public UserPrincipal(User user) {
        this.user = user;
        this.userId = user.getId().value().toLong();
        this.username = user.getUsername();
        this.authorities = user.getRoles() == null
                ? Set.of()
                : user.getRoles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toUnmodifiableSet());
    }

    // Lightweight — built from JWT claims, no DB hit
    public UserPrincipal(UserId userId, String username, Set<String> roles) {
        this.user = null;
        this.userId = userId.value().toLong();
        this.username = username;
        this.authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    public UserId getUserId() {
        return UserId.of(userId);
    }

    // The shared contract the other contexts read: the id as a plain long.
    @Override
    public long id() {
        return userId;
    }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return user != null ? user.getPasswordHash() : null; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isEnabled() { return user == null || user.isEnabled(); }
    @Override public boolean isAccountNonExpired() { return user == null || user.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return user == null || user.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return user == null || user.isCredentialsNonExpired(); }
}