package com.payflow.gateway.security;

import com.payflow.gateway.entity.Merchant;
import com.payflow.gateway.repository.MerchantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Кожен ендпоінт, звернений до мерчанта, автентифікується заголовком
 * {@code Authorization: Bearer <key>}. У разі успіху {@link MerchantPrincipal}
 * кладеться в security-контекст, щоб контролери могли обмежувати кожен запит
 * поточним мерчантом, не визначаючи його заново.
 *
 * <p>Відсутній або невпізнаний ключ тут лишається неавтентифікованим, а не
 * відхиляється одразу - правила доступу Spring Security (див. {@code SecurityConfig})
 * є єдиним місцем, що вирішує, що отримає неавтентифікований запит, тож це
 * рішення не дублюється тут ще раз.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final MerchantRepository merchantRepository;
    private final ApiKeyHasher apiKeyHasher;

    public ApiKeyAuthenticationFilter(MerchantRepository merchantRepository, ApiKeyHasher apiKeyHasher) {
        this.merchantRepository = merchantRepository;
        this.apiKeyHasher = apiKeyHasher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        try {
            extractApiKey(request)
                    .map(apiKeyHasher::hash)
                    .flatMap(merchantRepository::findByApiKeyHash)
                    .filter(Merchant::isActive)
                    .ifPresent(merchant -> {
                        MerchantPrincipal principal = new MerchantPrincipal(merchant.getId());
                        var authentication =
                                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        } catch (DataAccessException | TransactionException exception) {
            // Це фільтр, а не контролер - @RestControllerAdvice сюди не дотягнеться,
            // бо виняток стався до того, як DispatcherServlet узагалі почав
            // диспетчеризацію. Якщо дати йому просто вилетіти далі, Spring Boot
            // зробить внутрішній форвард на /error, той форвард ЗНОВУ пройде через
            // security-ланцюжок, але цей самий фільтр на error-dispatch вже НЕ
            // запуститься (OncePerRequestFilter.shouldNotFilterErrorDispatch()
            // за замовчуванням true) - автентифікація не встановиться, і клієнт
            // отримає оманливий 401 "не авторизовано" замість чесного "база
            // тимчасово перевантажена". Тому обробляємо це тут і зараз.
            //
            // Ловимо і DataAccessException, і TransactionException окремо, а не
            // спільного предка - це дві РІЗНІ гілки ієрархії Spring: перша про
            // невдалий запит до бази (з'єднання пропало під час виконання),
            // друга - про невдале ВІДКРИТТЯ транзакції (Hikari не встиг дати
            // з'єднання peer). CannotCreateTransactionException, яку кидає
            // вичерпаний пул, належить саме до другої гілки.
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            return;
        }

        chain.doFilter(request, response);
    }

    private Optional<String> extractApiKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER_PREFIX.length()));
    }
}
