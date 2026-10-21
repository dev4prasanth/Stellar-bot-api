package com.stellartrack.auth.api;

import com.stellartrack.auth.application.AdminProvisioningService;
import com.stellartrack.auth.application.AuthService;
import com.stellartrack.auth.application.AuthService.AdminProfile;
import com.stellartrack.auth.application.AuthTokens;
import com.stellartrack.auth.persistence.AdminUserEntity;
import com.stellartrack.auth.security.AdminPrincipal;
import com.stellartrack.common.api.ApiPaths;
import com.stellartrack.common.exception.ApplicationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.AUTH)
@Tag(name = "Authentication")
public class AuthController {

    /** Required on cookie-authenticated endpoints; a cross-site form post cannot set it. */
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_HEADER_VALUE = "StellarTrack";

    private final AuthService authService;
    private final AdminProvisioningService provisioningService;
    private final RefreshCookies refreshCookies;

    public AuthController(AuthService authService, AdminProvisioningService provisioningService,
            RefreshCookies refreshCookies) {
        this.authService = authService;
        this.provisioningService = provisioningService;
        this.refreshCookies = refreshCookies;
    }

    @PostMapping("/register")
    @Operation(summary = "Register the first administrator account (only succeeds when no admin exists yet)")
    public ResponseEntity<RegisterAdminResponse> register(@Valid @RequestBody RegisterAdminRequest request) {
        AdminUserEntity admin = provisioningService.registerFirstAdmin(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterAdminResponse(admin.getUsername()));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate the administrator and issue an access token (refresh token is set as an HttpOnly cookie)")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthTokens tokens = authService.login(request.username(), request.password(), http.getRemoteAddr());
        return tokenResponse(tokens);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh token and issue a new access token")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader) {
        requireCsrfHeader(csrfHeader);
        return tokenResponse(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session; all access tokens for it stop working immediately")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader,
            @AuthenticationPrincipal Jwt jwt) {
        requireCsrfHeader(csrfHeader);
        UUID sessionId = jwt == null ? null : AdminPrincipal.from(jwt).sessionId();
        authService.logout(refreshToken, sessionId);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Return the authenticated administrator's profile and session information")
    public AdminProfile me(@AuthenticationPrincipal Jwt jwt) {
        AdminPrincipal principal = AdminPrincipal.from(jwt);
        return authService.profile(principal.adminId(), principal.sessionId());
    }

    private ResponseEntity<AccessTokenResponse> tokenResponse(AuthTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookies.issue(tokens.refreshToken(), tokens.refreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccessTokenResponse(tokens.accessToken(), "Bearer", tokens.accessTokenExpiresAt(),
                        tokens.username()));
    }

    private static void requireCsrfHeader(String value) {
        if (!CSRF_HEADER_VALUE.equals(value)) {
            throw new MissingCsrfHeaderException();
        }
    }

    static class MissingCsrfHeaderException extends ApplicationException {
        MissingCsrfHeaderException() {
            super("CSRF_HEADER_REQUIRED", "Missing required request header.", Map.of());
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.FORBIDDEN;
        }
    }
}
