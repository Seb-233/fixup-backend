package com.fixup.shared.errors;

public record ErrorResponse(int status, String code, String message, String path) {
}
