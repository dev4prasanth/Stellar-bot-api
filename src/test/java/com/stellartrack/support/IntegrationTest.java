package com.stellartrack.support;

import com.stellartrack.auth.api.AccessTokenResponse;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Base for integration tests: real PostgreSQL (Testcontainers), full Spring context, MockMvc, fake exchange. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ContainersConfig.class, FakeExchangeConfig.class})
public abstract class IntegrationTest {

    protected static final String ADMIN = "test-admin";
    protected static final String PASSWORD = "Test-Password-123!";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper mapper;

    @Autowired
    protected FakeExchange exchange;

    /** Registers the single administrator once per test database (409 afterwards is expected). */
    protected void ensureAdmin() throws Exception {
        int status = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD))))
                .andReturn().getResponse().getStatus();
        if (status != 201 && status != 409) {
            throw new IllegalStateException("Admin registration failed with HTTP " + status);
        }
    }

    protected AccessTokenResponse login() throws Exception {
        ensureAdmin();
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", ADMIN, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), AccessTokenResponse.class);
    }

    protected String bearer() throws Exception {
        return "Bearer " + login().accessToken();
    }

    /** GET {@code path} as the administrator and parse the JSON body (expects 200). */
    protected JsonNode getJson(String token, String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** POST {@code body} as JSON and parse the response (empty object for an empty body). */
    protected JsonNode postJson(String token, String path, Object body, int expectedStatus) throws Exception {
        String content = mvc.perform(post(path).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return content.isBlank() ? mapper.createObjectNode() : mapper.readTree(content);
    }

    /** Waits up to 10 seconds for asynchronous work (event listeners) to satisfy {@code condition}. */
    protected static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Condition not met within 10 seconds");
    }
}
