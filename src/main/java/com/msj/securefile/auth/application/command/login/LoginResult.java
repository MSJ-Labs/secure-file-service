package com.msj.securefile.auth.application.command.login;

import com.msj.securefile.auth.application.result.UserProfile;

public record LoginResult(String accessToken, String refreshToken, UserProfile user) {}