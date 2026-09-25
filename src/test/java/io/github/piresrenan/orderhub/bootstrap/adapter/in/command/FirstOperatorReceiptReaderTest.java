package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Why: the receipt is untrusted deployment input and must be taken exactly or rejected.
 * Covers: the strict JSON shape, UTF-8, size and exactness rules of ADR-0022.
 * Prevents: silent normalization, coercion, or leaking receipt content through rejection messages.
 */
class FirstOperatorReceiptReaderTest {

    private static final String ISSUER = "https://identity.receipt.test";
    private static final String SUBJECT = "Exact-Subject-é-01";

    @TempDir
    Path directory;

    @Test
    void readsExactValuesWithoutNormalization() throws Exception {
        var request = FirstOperatorReceiptReader.read(file("{\"subject\":\"" + SUBJECT + "\",\"issuer\":\"" + ISSUER + "\"}"), UUID.randomUUID());
        assertThat(request.issuer()).isEqualTo(ISSUER);
        assertThat(request.subject()).isEqualTo(SUBJECT);
        assertThat(request.toString()).doesNotContain(ISSUER, SUBJECT);
    }

    @Test
    void rejectsEveryMalformedShapeWithoutEchoingContent() throws Exception {
        String[] invalid = {
                "", "[]", "null", "\"text\"", "{\"issuer\":\"" + ISSUER + "\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"s\",\"password\":\"x\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"a\",\"subject\":\"b\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":42}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":null}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\" s\"}",
                "{\"issuer\":\"" + ISSUER + " \",\"subject\":\"s\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"a\\u0000b\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"   \"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"" + "s".repeat(1025) + "\"}",
                "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"s\"}" + " ".repeat(20_000)};
        for (var content : invalid) {
            assertThatThrownBy(() -> FirstOperatorReceiptReader.read(file(content), UUID.randomUUID()))
                    .as(content.length() > 60 ? content.substring(0, 60) : content)
                    .isInstanceOf(InvalidBootstrapInputException.class)
                    .hasMessage("Bootstrap input is invalid")
                    .hasNoCause();
        }
    }

    @Test
    void rejectsMalformedUtf8AndMissingFiles() throws Exception {
        var malformed = directory.resolve("malformed.json");
        Files.write(malformed, new byte[] {'{', '"', (byte) 0xC3, '"', '}'});
        assertThatThrownBy(() -> FirstOperatorReceiptReader.read(malformed, UUID.randomUUID()))
                .isInstanceOf(InvalidBootstrapInputException.class);
        assertThatThrownBy(() -> FirstOperatorReceiptReader.read(directory.resolve("missing.json"), UUID.randomUUID()))
                .isInstanceOf(InvalidBootstrapInputException.class);
    }

    private Path file(String content) throws Exception {
        var file = Files.createTempFile(directory, "receipt", ".json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
