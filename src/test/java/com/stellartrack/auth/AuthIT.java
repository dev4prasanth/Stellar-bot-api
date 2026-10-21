package com.stellartrack.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stellartrack.auth.api.AccessTokenResponse;
import com.stellartrack.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MvcResult;

class AuthIT extends IntegrationTest {

    @Autowired
    JwtEncoder encoder;

    @BeforeEach
    void administratorExists() throws Exception {
        ensureAdmin();
    }

    @Test
    void loginIssuesTokenAndHttpOnlyRefreshCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("st_refresh=").contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
        AccessTokenResponse token = mapper.readValue(result.getResponse().getContentAsString(), AccessTokenResponse.class);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registrationOnlyWorksWhileNoAdministratorExists() throws Exception {
        ensureAdmin();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "intruder", "password", "Another-Pass-123!"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_ALREADY_EXISTS"));
    }

    @Test
    void invalidCredentialsGetGenericError() throws Exception {
        for (Map<String, String> body : List.of(Map.of("username", ADMIN, "password", "wrong-password-1!"),
                Map.of("username", "nobody", "password", "wrong-password-1!"))) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Invalid username or password."));
        }
    }

    @Test
    void protectedEndpointsDenyByDefault() throws Exception {
        mvc.perform(get("/api/v1/configurations")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/configurations").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void tamperedExpiredAndWrongRoleTokensAreRejected() throws Exception {
        String valid = login().accessToken();
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("A") ? "BB" : "AA");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token(Instant.now().minusSeconds(60), List.of("ADMIN"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token(Instant.now().plusSeconds(300), List.of("USER"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesIssuedAccessTokens() throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD)))).andReturn();
        String access = mapper.readValue(loginResult.getResponse().getContentAsString(), AccessTokenResponse.class).accessToken();
        Cookie refresh = loginResult.getResponse().getCookie("st_refresh");

        mvc.perform(post("/api/v1/auth/logout").cookie(refresh)).andExpect(status().isForbidden()); // CSRF header required
        mvc.perform(post("/api/v1/auth/logout").cookie(refresh).header("X-Requested-With", "StellarTrack"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheSession() throws Exception {
        MvcResult loginResult = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD)))).andReturn();
        Cookie first = loginResult.getResponse().getCookie("st_refresh");
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "StellarTrack"))
                .andExpect(status().isOk()).andReturn();
        String newAccess = mapper.readValue(refreshed.getResponse().getContentAsString(), AccessTokenResponse.class).accessToken();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newAccess)).andExpect(status().isOk());

        // Reusing the already-rotated token signals theft: the whole session is revoked.
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "StellarTrack"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newAccess)).andExpect(status().isUnauthorized());
    }

    private String token(Instant expiresAt, List<String> roles) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("stellar-track").audience(List.of("stellar-track-web"))
                .subject(UUID.randomUUID().toString()).issuedAt(expiresAt.minusSeconds(600)).expiresAt(expiresAt)
                .claim("sid", UUID.randomUUID().toString()).claim("roles", roles).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
