package com.payflow.gateway.security;

import com.payflow.gateway.domain.Merchant;
import com.payflow.gateway.domain.MerchantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
