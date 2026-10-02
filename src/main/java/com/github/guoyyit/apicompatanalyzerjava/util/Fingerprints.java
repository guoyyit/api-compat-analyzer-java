package com.github.guoyyit.apicompatanalyzerjava.util;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic content fingerprints.
 *
 * <p>A fingerprint is a SHA-256 hex digest over the complete content of an
 * analysis input (version label plus every file path and file body), encoded
 * in a canonical order. It contains no clock, process or machine state, so
 * identical inputs always yield identical fingerprints, even across restarts,
 * and any change to a path, a file body or the version label changes it.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /** Fingerprint of a full source-tree input: version label plus every file. */
    public static String ofInputs(String version, Map<String, String> sources) {
        MessageDigest digest = sha256();
        digest.update(("version=" + nullToEmpty(version) + "\n").getBytes(StandardCharsets.UTF_8));
        digest.update(("files=" + sources.size() + "\n").getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, String> e : new TreeMap<>(sources).entrySet()) {
            byte[] path = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] body = nullToEmpty(e.getValue()).getBytes(StandardCharsets.UTF_8);
            digest.update(path);
            digest.update((byte) 0);
            digest.update(String.valueOf(body.length).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            digest.update((byte) '\n');
        }
        return toHex(digest.digest());
    }

    /** Combines two fingerprints into one stable digest (e.g. baseline + current). */
    public static String combine(String first, String second) {
        MessageDigest digest = sha256();
        digest.update(nullToEmpty(first).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(nullToEmpty(second).getBytes(StandardCharsets.UTF_8));
        return toHex(digest.digest());
    }

    /**
     * Fallback fingerprint derived from a baseline's persisted API contents,
     * used only for baselines loaded from files that predate raw fingerprints.
     * Covers every element field and the type hierarchy so any content change
     * in the baseline still changes the derived report identity.
     */
    public static String ofSnapshot(ApiSnapshot snapshot) {
        MessageDigest digest = sha256();
        digest.update(("version=" + nullToEmpty(snapshot.version()) + "\n").getBytes(StandardCharsets.UTF_8));
        new TreeMap<>(snapshot.elements()).forEach((key, el) -> {
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String canonical = el.kind() + "|" + el.ownerType() + "|" + el.name() + "|"
                    + el.returnType() + "|" + el.parameterTypes() + "|" + el.access() + "|"
                    + new java.util.TreeSet<>(el.modifiers()) + "|" + el.sourceFile() + "|" + el.line();
            digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        new TreeMap<>(snapshot.typeSuperclasses()).forEach((type, superClass) -> {
            digest.update(type.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(nullToEmpty(superClass).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        new TreeMap<>(snapshot.typeSupertypes()).forEach((type, supers) -> {
            digest.update(type.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(new java.util.TreeSet<>(supers).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        return toHex(digest.digest());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
