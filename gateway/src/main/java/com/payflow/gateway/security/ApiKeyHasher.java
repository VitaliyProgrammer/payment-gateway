package com.payflow.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Звичайний SHA-256, а не повільний хеш для паролів (bcrypt/argon2). API-ключі -
 * це високоентропійні випадкові токени, які мерчант не обирає сам, на відміну від
 * паролів, тож тут немає ризику словникової атаки чи перебору, від якого треба
 * навмисно уповільнюватись - є лише ризик витоку збереженого значення, а від
 * нього вже захищає й швидкий односторонній хеш.
 */
@Component
public class ApiKeyHasher {

    public String hash(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
