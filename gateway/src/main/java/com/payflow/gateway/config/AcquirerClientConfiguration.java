package com.payflow.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AcquirerClientConfiguration {

    @Bean
    public RestClient acquirerRestClient(@Value("${payflow.acquirer.base-url}") String baseUrl) {
        // Явні таймаути, а не значення "за замовчуванням" (яких у стандартної
        // фабрики фактично немає - вона чекала б без обмежень): якщо еквайр
        // завис, воркер має рано чи пізно здатись, а не заблокуватись назавжди
        // на одному платежі.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(5_000);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
