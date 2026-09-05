package com.payflow.gateway.processing;

/**
 * Свій DTO, а не спільний клас з mock-acquirer - шлюз і еквайр це окремі
 * сервіси з окремими деплоями, і контракт між ними - це JSON через мережу, а
 * не спільний Java-тип. Збіг імен полів навмисний і має лишатись синхронним
 * вручну, так само як довелось би синхронізувати контракт зі справжнім банком.
 */
public record AcquirerChargeRequest(String reference, long amount, String currency) {
}
