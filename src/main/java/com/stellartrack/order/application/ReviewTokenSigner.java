package com.stellartrack.order.application;

import com.stellartrack.common.exception.ConflictException;
import com.stellartrack.common.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs the exact terms the administrator reviewed so "Confirm &amp; Place" cannot silently submit different
 * terms. The key is random per process; a restart simply requires a fresh review.
 */
@Component
public class ReviewTokenSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key = new byte[32];
    private final JsonCodec json;
    private final Clock clock;

    public ReviewTokenSigner(JsonCodec json, Clock clock) {
        new SecureRandom().nextBytes(key);
        this.json = json;
        this.clock = clock;
    }

    public String sign(ReviewedTerms terms) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.write(terms).getBytes(StandardCharsets.UTF_8));
        return payload + "." + mac(payload);
    }

    public ReviewedTerms verify(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0) {
            throw invalid();
        }
        String payload = token.substring(0, dot);
        byte[] expected = mac(payload).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid();
        }
        ReviewedTerms terms = json.read(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8),
                ReviewedTerms.class);
        if (terms.expiresAt().isBefore(clock.instant())) {
            throw new ConflictException("REVIEW_EXPIRED", "The review has expired; review the bet again");
        }
        return terms;
    }

    private String mac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static ConflictException invalid() {
        return new ConflictException("REVIEW_INVALID", "The review token is invalid; review the bet again");
    }

    /** The reviewed terms embedded in the token. */
    public record ReviewedTerms(String marketId, long selectionId, String side, java.math.BigDecimal price,
            java.math.BigDecimal stake, java.util.UUID configurationId, Integer configurationVersion,
            String persistenceType, Instant expiresAt) {
    }
}
