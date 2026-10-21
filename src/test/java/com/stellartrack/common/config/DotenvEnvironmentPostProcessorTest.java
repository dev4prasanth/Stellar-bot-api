package com.stellartrack.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class DotenvEnvironmentPostProcessorTest {

    private final DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(new DeferredLogs());

    @Test
    void parsesValuesLiterallyLikeDockerCompose() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of(
                "# comment",
                "",
                "ST_BETFAIR_CERT_KEYSTORE=C:\\KMSS\\infra\\certificate\\client.p12",
                "ST_BETFAIR_ENABLED=true",
                "QUOTED=\"a b\"",
                "export EXPORTED=x=y",
                "not a setting"));

        assertThat(values)
                .containsEntry("ST_BETFAIR_CERT_KEYSTORE", "C:\\KMSS\\infra\\certificate\\client.p12")
                .containsEntry("ST_BETFAIR_ENABLED", "true")
                .containsEntry("QUOTED", "a b")
                .containsEntry("EXPORTED", "x=y")
                .hasSize(4);
    }

    @Test
    void explicitFileHasLowerPrecedenceThanRealEnvironment(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve(".env"), "ST_BETFAIR_ENABLED=true\nST_BETFAIR_USERNAME=fromFile\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("ide",
                Map.of("ST_ENV_FILE", file.toString(), "ST_BETFAIR_USERNAME", "fromIde")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("ST_BETFAIR_ENABLED")).isEqualTo("true");
        assertThat(environment.getProperty("ST_BETFAIR_USERNAME")).isEqualTo("fromIde");
    }

    @Test
    void doesNothingOutsideTheLocalProfileWithoutAnExplicitFile() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains(DotenvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    void missingExplicitFileFailsFast(@TempDir Path dir) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("ide",
                Map.of("ST_ENV_FILE", dir.resolve("absent.env").toString())));

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .hasMessageContaining("ST_ENV_FILE");
    }
}
