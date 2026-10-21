package com.stellartrack.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterAdminRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        return "RegisterAdminRequest[username=" + username + ", password=[REDACTED]]";
    }
}
