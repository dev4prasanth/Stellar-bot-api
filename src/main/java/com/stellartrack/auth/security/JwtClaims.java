package com.stellartrack.auth.security;

/** Custom claim names used in application access tokens. */
public final class JwtClaims {

    public static final String SESSION_ID = "sid";
    public static final String ROLES = "roles";
    public static final String USERNAME = "preferred_username";

    private JwtClaims() {
    }
}
