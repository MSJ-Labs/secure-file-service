package com.msj.securefile.auth.application.command.register;

import com.msj.securefile.auth.application.port.out.PasswordHasher;
import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.application.result.UserProfile;
import com.msj.securefile.auth.domain.user.EmailAlreadyExistsException;
import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UsernameAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterCommandHandler {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    @Transactional
    public UserProfile handle(RegisterCommand command) {
        log.info("Registering new user: {}", command.username());

        if (userRepository.existsByUsername(command.username())) {
            throw new UsernameAlreadyExistsException(command.username());
        }
        if (userRepository.existsByEmail(command.email())) {
            throw new EmailAlreadyExistsException(command.email());
        }

        String passwordHash = passwordHasher.encode(command.password());

        User user = User.register(
                command.username(),
                command.email(),
                passwordHash,
                command.firstName(),
                command.lastName(),
                LocalDateTime.now(clock)
        );

        User saved = userRepository.save(user);
        log.info("User registered successfully: {}", saved.getId().asString());
        return UserProfile.from(saved);
    }
}