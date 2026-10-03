package com.msj.securefile.auth.application.command.register;

public record RegisterCommand(
        String username,
        String email,
        String password,
        String firstName,
        String lastName
) {}