package com.payflow.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Метадані для OpenAPI-опису (springdoc). Самі ендпоінти, параметри й схеми
 * springdoc виводить із контролера й DTO - тут лише те, чого в сигнатурах немає:
 * назва, опис і те, що кожен виклик автентифікується Bearer-ключем мерчанта
 * (щоб у Swagger UI з'явилася кнопка Authorize).
 */
@Configuration
public class OpenApiConfiguration {

    private static final String API_KEY_SCHEME = "merchantApiKey";

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payflow payment gateway API")
                        .version("v1")
                        .description("""
                                Merchant-facing gateway: create, capture, cancel and refund payments.
                                Every mutating call is authenticated with a merchant API key
                                (Authorization: Bearer <key>) and de-duplicated with an Idempotency-Key
                                header. The migrations seed a demo merchant whose key is
                                'demo-merchant-api-key'."""))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("Merchant API key issued out of band; sent as a bearer token.")));
    }
}
