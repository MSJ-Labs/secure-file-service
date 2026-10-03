package com.msj.securefile.auth.application.query;

import com.msj.securefile.auth.application.port.out.UserRepository;
import com.msj.securefile.auth.application.result.UserProfile;
import com.msj.securefile.auth.domain.user.User;
import com.msj.securefile.auth.domain.user.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static com.msj.securefile.auth.support.UserTestFactory.activeUser;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetUserProfileQueryHandlerTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GetUserProfileQueryHandler handler;

    @Test
    void handle_userExists_returnsUser() {
        User user = activeUser("jdoe");
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));

        UserProfile result = handler.handle(new GetUserProfileQuery("jdoe"));

        assertThat(result.username()).isEqualTo("jdoe");
    }

    @Test
    void handle_userNotFound_throwsUserNotFoundException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
        GetUserProfileQuery query = new GetUserProfileQuery("unknown");

        assertThatThrownBy(() -> handler.handle(query))
                .isInstanceOf(UserNotFoundException.class);
    }
}