package com.stellartrack.common.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a Docker-Compose style {@code .env} file (KEY=VALUE per line) so a backend started outside Docker, e.g. from
 * an IDE, sees the same settings as the containers.
 * <ul>
 *   <li>Explicit: {@code ST_ENV_FILE=/path/to/.env} (any profile).</li>
 *   <li>Implicit, {@code local} profile only: {@code .env} in the working directory (the backend repository
 *   root).</li>
 * </ul>
 * Values are taken literally, exactly as Docker Compose does (backslashes in Windows paths are kept; surrounding
 * quotes are removed). The file has the lowest precedence: real environment variables, system properties and IDE run
 * settings always win. Values are never logged.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "dotenvFile";
    static final String ENV_FILE_VARIABLE = "ST_ENV_FILE";
    private static final Path LOCAL_FILE = Path.of(".env");

    private final Log log;

    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        locate(environment).ifPresent(file -> {
            Map<String, Object> values = parse(file);
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, values));
            // Only the location and key count are reported; values may be secrets.
            log.info("Loaded " + values.size() + " settings from " + file.toAbsolutePath().normalize()
                    + " (environment variables take precedence)");
        });
    }

    private static Optional<Path> locate(ConfigurableEnvironment environment) {
        String explicit = environment.getProperty(ENV_FILE_VARIABLE);
        if (explicit != null && !explicit.isBlank()) {
            Path file = Path.of(explicit.trim());
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException(ENV_FILE_VARIABLE + " points to a missing file: " + file);
            }
            return Optional.of(file);
        }
        if (!Arrays.asList(environment.getActiveProfiles()).contains("local")) {
            return Optional.empty();
        }
        return Optional.of(LOCAL_FILE).filter(Files::isRegularFile);
    }

    static Map<String, Object> parse(Path file) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read environment file " + file, e);
        }
    }

    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                continue;
            }
            values.put(key, unquote(line.substring(equals + 1).strip()));
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** After config data (application.yml and profiles) so active profiles are known. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
