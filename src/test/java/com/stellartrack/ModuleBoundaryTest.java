package com.stellartrack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Architectural guard rails checked against the source tree:
 * replay is isolated from order submission; decision and market logic do not depend on order execution;
 * domain modules depend on ports, not on the Betfair adapter; Watch Mode (simulation) has no path to real order
 * submission, real order/settlement records or account money, and live modules do not depend on Watch.
 */
class ModuleBoundaryTest {

    private static final Path SOURCE = Path.of("src/main/java/com/stellartrack");

    @ParameterizedTest(name = "{0} must not import {1}")
    @CsvSource({
            "replay, com.stellartrack.order",
            "replay, com.stellartrack.betfair",
            "decision, com.stellartrack.order",
            "decision, com.stellartrack.betfair",
            "market/domain, com.stellartrack.betfair",
            "market/filter, com.stellartrack.betfair",
            "order/domain, com.stellartrack.betfair",
            "order/application, com.stellartrack.betfair",
            "staking, com.stellartrack.betfair",
            "messaging, com.stellartrack.order",
            "reporting, com.stellartrack.betfair",
            "watch, com.stellartrack.betfair",
            "watch, com.stellartrack.order.persistence",
            "watch, com.stellartrack.capital.persistence",
            "order, com.stellartrack.watch",
            "automation, com.stellartrack.watch",
            "decision, com.stellartrack.watch",
            "reporting, com.stellartrack.watch",
            "capital, com.stellartrack.watch"})
    void moduleDoesNotDependOn(String module, String forbiddenPackage) throws IOException {
        assertThat(importing(module, "import " + forbiddenPackage + "."))
                .as("files in %s importing %s", module, forbiddenPackage).isEmpty();
    }

    @ParameterizedTest(name = "{0} must not import {1}")
    @CsvSource({
            "watch, com.stellartrack.order.port.OrderExecutionPort",
            "watch, com.stellartrack.order.application.OrderPlacementService",
            "watch, com.stellartrack.order.application.ApprovedRecommendationExecutor",
            "watch, com.stellartrack.order.application.ManualTicketService"})
    void moduleDoesNotUseClass(String module, String forbiddenClass) throws IOException {
        assertThat(importing(module, "import " + forbiddenClass + ";"))
                .as("files in %s importing %s", module, forbiddenClass).isEmpty();
    }

    private static List<String> importing(String module, String importStatement) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE.resolve(module))) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(importStatement);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
    }
}
