package com.msj.securefile.auth.api;

import com.msj.securefile.auth.api.dto.UserProfileResponse;
import com.msj.securefile.auth.application.query.GetUserProfileQuery;
import com.msj.securefile.auth.application.query.GetUserProfileQueryHandler;
import com.msj.securefile.auth.infrastructure.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final GetUserProfileQueryHandler getProfileHandler;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        var user = getProfileHandler.handle(new GetUserProfileQuery(principal.getUsername()));
        return ResponseEntity.ok(UserProfileResponse.from(user));
    }
}