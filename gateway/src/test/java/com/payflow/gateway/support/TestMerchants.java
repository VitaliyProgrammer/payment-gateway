package com.payflow.gateway.support;

import java.util.UUID;

/** Відповідає демо-мерчанту, який заповнюється міграцією {@code V3__seed_demo_merchant.sql}. */
public final class TestMerchants {

    public static final UUID DEMO_MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String DEMO_API_KEY = "demo-merchant-api-key";

    private TestMerchants() {
    }
}
