package com.github.guoyyit.apicompatanalyzerjava.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic SHA-256 helpers used for content addressing and reproducible report ids. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Hash a map of named parts in a stable way, independent of iteration order.
     * Keys are sorted, each value is hashed, and {@code name:hash} lines are concatenated.
     */
    public static String contentHash(Map<String, String> namedParts) {
        TreeMap<String, String> sorted = new TreeMap<>(namedParts);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append(':').append(sha256(entry.getValue())).append('\n');
        }
        return sha256(sb.toString());
    }
}
