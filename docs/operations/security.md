# Security, roles and token lifecycle

## Roles and endpoint access

| Endpoint | Access |
| --- | --- |
| `POST /api/v1/auth/login`, `/auth/refresh`, `/auth/logout` | Public (refresh/logout require header `X-Requested-With: StellarTrack` and the refresh cookie) |
| `POST /api/v1/auth/register` | Public, **only succeeds while no administrator exists** (409 afterwards; single-admin DB index) |
| `GET /actuator/health`, `/health/liveness`, `/health/readiness` | Public |
| Everything else (`/api/**`, other actuator endpoints, OpenAPI unless `publicApiDocs`) | `ROLE_ADMIN` with a valid access token (deny by default) |

## Token lifecycle

- **Access token**: HS256 JWT, 10 min (`ST_JWT_ACCESS_TTL`), claims `iss`, `aud`, `sub` (admin id), `sid`
  (session id), `roles`, `jti`. Validated for signature (HS256 only), expiry, issuer, audience, role and an
  **active server-side session** on every request. Held only in browser memory.
- **Refresh token**: 32 random bytes, HttpOnly + Secure + SameSite=Strict cookie scoped to `/api/v1/auth`;
  only its SHA-256 hash is stored. Single use and rotated on every refresh; presenting a used token revokes the
  whole session. Absolute session lifetime: `ST_JWT_REFRESH_TTL` (12 h).
- **Logout** revokes the session, so every access token for it stops working immediately.
- Responses: `401 UNAUTHORIZED` (no token), `401 SESSION_EXPIRED` (invalid/expired/revoked token), `403 FORBIDDEN`.

## Other controls

BCrypt (cost 12) password hashing; password policy 12+ chars, ≤ 72 bytes, letters + digits + symbol; per-IP login
rate limit (10/min) and account lockout after 5 failures (15 min); generic login errors with timing equalization;
restrictive CORS allow-list; security headers (CSP, HSTS, no-referrer, frame denial); logins, logouts, token
refreshes and refresh-token reuse are deliberately **not** audited (routine noise), and older rows of those actions
are hidden from audit search, although lockout and reuse revocation still apply; secrets redacted from audit details and never logged; uploads parsed as data only
(staking plans reject formulas/macros; replay files stored under generated names).

**Note on first-time registration:** the register endpoint is open until an administrator exists. Deploy with the
backend unreachable from untrusted networks until setup is complete, or front it with an access-restricted proxy.
