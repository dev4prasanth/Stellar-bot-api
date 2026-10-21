package com.stellartrack.messaging.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The five planets of the Layer 2/3 contracts and the runner (cloth) numbers each one owns, exactly as Layer 2's
 * {@code Planet} enum defines them.
 */
public enum Planet {
    MARS(1, 8, 16, 17, 32, 33, 34, 35),
    VENUS(2, 7, 14, 15, 28, 29, 30, 31),
    MERCURY(3, 6, 12, 13, 24, 25, 26, 27),
    MOON(4, 9, 18, 19, 36, 37, 38, 39),
    SUN(5, 10, 11, 20, 21, 22, 23, 40, 41);

    private final List<Integer> runnerNumbers;

    Planet(Integer... runnerNumbers) {
        this.runnerNumbers = List.of(runnerNumbers);
    }

    public List<Integer> runnerNumbers() {
        return runnerNumbers;
    }

    /** Empty for runner numbers outside the published planet table (e.g. 42+). */
    public static Optional<Planet> ofRunner(Integer runnerNumber) {
        if (runnerNumber == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.runnerNumbers.contains(runnerNumber)).findFirst();
    }

    public static boolean isKnown(String name) {
        return name != null && Arrays.stream(values()).anyMatch(p -> p.name().equals(name));
    }
}
