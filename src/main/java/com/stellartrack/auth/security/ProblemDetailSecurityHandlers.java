package com.stellartrack.auth.security;

import com.stellartrack.common.api.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 responses rendered as the same problem documents the rest of the API returns.
 * A 401 carries code {@code SESSION_EXPIRED} only when a bearer token was presented but rejected,
 * so the UI can distinguish "log in" from "your session ended".
 */
@Component
public class ProblemDetailSecurityHandlers {

    private final JsonMapper mapper;

    public ProblemDetailSecurityHandlers(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            String authorization = request.getHeader("Authorization");
            boolean tokenPresented = authorization != null && authorization.startsWith("Bearer ");
            write(response, GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED,
                    tokenPresented ? "SESSION_EXPIRED" : "UNAUTHORIZED",
                    tokenPresented ? "Your session is invalid or has expired." : "Authentication is required."));
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN,
                "FORBIDDEN", "You are not allowed to perform this action."));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (problem.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
