package com.payflow.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Невелика спільна утиліта: рахує SHA-256 від рядка і повертає його у
 * шістнадцятковому вигляді. Використовується і для хешування API-ключів
 * ({@code ApiKeyHasher}), і для "відбитка" тіла запиту в ідемпотентності -
 * в обох випадках потрібне лише те саме одностороннє хешування без солі.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 недоступний у цій JVM!", exception);
        }
    }
}
