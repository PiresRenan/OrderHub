package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the deployment-owned Identity receipt: a strict UTF-8 JSON object with
 * exactly the string members {@code issuer} and {@code subject}.
 *
 * <p>Unknown or duplicate members, other JSON types, malformed UTF-8 and files
 * larger than {@link #MAX_BYTES} are rejected. Values are passed on exactly;
 * nothing is trimmed or normalized. Failures never carry file content.</p>
 */
final class FirstOperatorReceiptReader {

    /** Generous for two 1024-byte values with JSON escaping; bounds untrusted input. */
    static final int MAX_BYTES = 16 * 1024;

    private static final Set<String> MEMBERS = Set.of("issuer", "subject");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private FirstOperatorReceiptReader() {
    }

    /**
     * Builds the ceremony request from the receipt file and the operation identifier.
     *
     * @param receipt     receipt file path
     * @param operationId OrderHub ceremony operation identifier
     * @return validated exact request
     * @throws InvalidBootstrapInputException when the receipt or operation id is unusable
     */
    static FirstOperatorBootstrapRequest read(Path receipt, UUID operationId) {
        try {
            if (Files.size(receipt) > MAX_BYTES) {
                throw new InvalidBootstrapInputException();
            }
            var text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(receipt)))
                    .toString();
            var node = JSON.readTree(text);
            if (node == null || !node.isObject() || node.size() != MEMBERS.size() || !MEMBERS.stream().allMatch(node::has)) {
                throw new InvalidBootstrapInputException();
            }
            return new FirstOperatorBootstrapRequest(string(node, "issuer"), string(node, "subject"), operationId);
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            throw new InvalidBootstrapInputException();
        }
    }

    /** Accepts only JSON strings, never numbers or nulls coerced into text. */
    private static String string(JsonNode node, String member) {
        var value = node.get(member);
        if (value == null || !value.isString()) {
            throw new InvalidBootstrapInputException();
        }
        return value.asString();
    }
}
