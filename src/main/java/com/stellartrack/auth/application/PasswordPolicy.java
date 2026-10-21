package com.stellartrack.auth.application;

import com.stellartrack.common.exception.BusinessRuleException;
import java.nio.charset.StandardCharsets;

/** Administrator password rules. BCrypt only uses the first 72 bytes, so longer passwords are rejected. */
public final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BusinessRuleException("PASSWORD_POLICY",
                    "Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessRuleException("PASSWORD_POLICY",
                    "Password must be at most " + MAX_BYTES + " bytes.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasOther = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!(hasLetter && hasDigit && hasOther)) {
            throw new BusinessRuleException("PASSWORD_POLICY",
                    "Password must contain letters, digits, and a symbol.");
        }
    }
}
