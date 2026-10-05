package com.msj.securefile.storage.application.command.initiateupload;

public record InitiateUploadCommand(String name, long declaredSize) {
}