package com.msj.securefile.auth.infrastructure.security;

import com.msj.securefile.auth.domain.user.User;
import lombok.Getter;
import org.springframework.lang.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class UserPrincipal implements UserDetails {

    @Getter @Nullable
    private final transient User user;
    private final String username;
    private final Set<GrantedAuthority> authorities;

    // Full user loaded from DB (registration, profile queries)
    public UserPrincipal(User user) {
        this.user = user;
        this.username = user.getUsername();
        this.authorities = user.getRoles() == null
                ? Set.of()
                : user.getRoles().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toUnmodifiableSet());
    }

    // Lightweight — built from JWT claims, no DB hit
    public UserPrincipal(String username, Set<String> roles) {
        this.user = null;
        this.username = username;
        this.authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return user != null ? user.getPasswordHash() : null; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public boolean isEnabled() { return user == null || user.isEnabled(); }
    @Override public boolean isAccountNonExpired() { return user == null || user.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return user == null || user.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return user == null || user.isCredentialsNonExpired(); }
}