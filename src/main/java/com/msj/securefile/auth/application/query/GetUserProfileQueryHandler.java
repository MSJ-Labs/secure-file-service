package com.msj.securefile.auth.application.query;

import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.application.result.UserProfile;
import com.msj.securefile.auth.domain.user.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetUserProfileQueryHandler {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserProfile handle(GetUserProfileQuery query) {
        return userRepository.findByUsername(query.username())
                .map(UserProfile::from)
                .orElseThrow(() -> UserNotFoundException.forUsername(query.username()));
    }
}