package com.stellartrack.replay.application;

import com.stellartrack.replay.config.ReplayProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded replay files under generated names only, so user-supplied filenames can never influence
 * the storage path (no path traversal). Files are never executed or interpreted beyond parsing as data.
 */
@Component
public class ReplayFileStorage {

    private final Path root;

    public ReplayFileStorage(ReplayProperties properties) {
        this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
    }

    public String store(InputStream content, String extension) throws IOException {
        Files.createDirectories(root);
        String key = UUID.randomUUID() + "." + extension;
        Path target = resolve(key);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return key;
    }

    public Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
