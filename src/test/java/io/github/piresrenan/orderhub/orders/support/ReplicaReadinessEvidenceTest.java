package io.github.piresrenan.orderhub.orders.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Why: the multi-replica acceptance test reads a replica's readiness evidence as soon as the ready
 * file exists.
 * Covers: publication observed by a reader that polls for existence and reads at once, exactly as
 * the acceptance test does, with a payload large enough to keep any non-atomic write window open.
 * Prevents: empty or partial evidence being read as readiness ("Replica PID evidence is missing").
 */
class ReplicaReadinessEvidenceTest {

    @Test
    void readyFileBecomesVisibleOnlyWithItsCompleteEvidence(
            @TempDir Path directory)
            throws Exception {

        var evidence =
                "pid=12345"
                        + System.lineSeparator()
                        + "x".repeat(1024 * 1024)
                        + System.lineSeparator()
                        + "datasource=com.zaxxer.hikari.HikariDataSource";

        for (var trial = 0; trial < 3; trial++) {

            var readyPath =
                    directory.resolve(
                            "ready-" + trial);

            var publication =
                    CompletableFuture.runAsync(() -> {
                        try {
                            ReplicaReadinessEvidence.publish(
                                    readyPath,
                                    evidence);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(
                                    exception);
                        }
                    });

            while (!Files.exists(readyPath)
                    && !publication.isDone()) {
                Thread.onSpinWait();
            }

            var observed =
                    Files.readString(
                            readyPath,
                            StandardCharsets.UTF_8);

            publication.join();

            assertThat(observed.length())
                    .as("observed evidence length, trial %d", trial)
                    .isEqualTo(evidence.length());

            assertThat(observed.equals(evidence))
                    .as("observed evidence content, trial %d", trial)
                    .isTrue();

            assertThat(directory.resolve("ready-" + trial + ".tmp"))
                    .as("temporary evidence file, trial %d", trial)
                    .doesNotExist();
        }
    }
}
