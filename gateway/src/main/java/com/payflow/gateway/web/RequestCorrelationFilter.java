package com.payflow.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Дає кожному запиту наскрізний ідентифікатор: бере з заголовка
 * {@code X-Request-Id}, якщо клієнт його прислав, інакше генерує. Кладе в MDC
 * під ключем {@code requestId} (звідки він потрапляє в кожен рядок логу - і в
 * JSON, і в текстовий) і повертає тим самим заголовком у відповіді, щоб клієнт
 * міг послатися на конкретний запит у зверненні до підтримки.
 *
 * <p>Свій фільтр, а не Micrometer Tracing: тут не потрібен повний
 * distributed-tracing стек з експортером - лише один id на запит.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
