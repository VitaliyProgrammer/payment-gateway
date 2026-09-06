package com.payflow.gateway.security;

import com.payflow.gateway.repository.MerchantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;

@Configuration
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, MerchantRepository merchantRepository,
            ApiKeyHasher apiKeyHasher) throws Exception {

        http
                // Це чистий JSON API, який викликають бекенди мерчантів, а не браузери
                // з кукі-сесіями - тут просто немає сесії, яку міг би атакувати CSRF.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        // Опис API читається без ключа - це документація, а не самі платежі.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(merchantRepository, apiKeyHasher),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
