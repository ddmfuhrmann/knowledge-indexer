package io.github.ddmfuhrmann.kindexer.hash;

import io.github.ddmfuhrmann.kindexer.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 of the canonical (compact, key-sorted) JSON of a value. This is the anchor of the
 * whole design: a deterministic artifact hashes to the same value whenever its content is
 * unchanged, so the enrichment cache can be keyed by it and the LLM is only re-consulted when
 * the underlying code material actually moved.
 */
public final class ContentHash {

    private ContentHash() {}

    /** {@code "sha256:" + hex} of the canonical JSON of {@code value}. */
    public static String of(Object value) {
        try {
            byte[] json = Json.canonical().writeValueAsBytes(value);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize for hashing: " + value.getClass(), e);
        }
    }
}
