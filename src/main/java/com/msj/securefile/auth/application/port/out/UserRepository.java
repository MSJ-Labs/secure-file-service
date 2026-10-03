package com.msj.securefile.auth.application.port.out;

import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserId;

import java.util.Optional;

/**
 * Output port — owned by the application, implemented by the persistence adapter.
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findById(UserId id);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}