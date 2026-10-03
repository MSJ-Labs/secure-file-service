package com.msj.securefile.auth.application.command.logout;

public record LogoutCommand(String username, String rawRefreshToken) {}