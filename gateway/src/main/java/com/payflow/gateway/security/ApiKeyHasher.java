package com.payflow.gateway.security;

import com.payflow.gateway.util.Sha256;
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
        return Sha256.hex(rawApiKey);
    }
}
