package com.stellartrack.auth.security;

import com.stellartrack.auth.config.SecurityProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class AccessTokenIssuer {

    public record IssuedToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(UUID adminId, String username, String role, UUID sessionId, Instant sessionExpiresAt) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        if (expiresAt.isAfter(sessionExpiresAt)) {
            expiresAt = sessionExpiresAt;
        }
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(adminId.toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim(JwtClaims.SESSION_ID, sessionId.toString())
                .claim(JwtClaims.ROLES, List.of(role))
                .claim(JwtClaims.USERNAME, username)
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.ALGORITHM).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
