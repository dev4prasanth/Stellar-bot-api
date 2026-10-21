package com.stellartrack.auth.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.stellartrack.auth.config.SecurityProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HMAC-SHA256 signed access tokens. The decoder accepts only HS256 (so {@code alg=none} and algorithm
 * substitution are rejected) and validates expiry, issuer, audience and the server-side session.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSigningKey(SecurityProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("stellartrack.security.jwt.secret (ST_JWT_SECRET) must be configured");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ST_JWT_SECRET must be base64 encoded", e);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("ST_JWT_SECRET must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, SecurityProperties properties, SessionActiveValidator sessionValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(ALGORITHM).build();
        String audience = properties.jwt().audience();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(Duration.ofSeconds(5)),
                new JwtIssuerValidator(properties.jwt().issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<List<String>>(JwtClaims.ROLES, roles -> roles != null && roles.contains("ADMIN")),
                sessionValidator));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
