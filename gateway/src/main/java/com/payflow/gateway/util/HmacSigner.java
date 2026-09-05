package com.payflow.gateway.util;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC, а не звичайний хеш (як Sha256 для API-ключів): підпис має залежати не
 * лише від тіла повідомлення, а й від секрету, відомого тільки нам і
 * мерчанту - інакше будь-хто міг би підробити вебхук, обчисливши хеш самого
 * тіла без знання секрету.
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Не вдалось підписати вебхук", exception);
        }
    }
}
