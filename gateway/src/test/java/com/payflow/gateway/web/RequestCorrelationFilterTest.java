package com.payflow.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void generatesAnIdWhenTheClientSendsNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] mdcDuringRequest = new String[1];
        FilterChain chain = (req, res) -> mdcDuringRequest[0] = MDC.get("requestId");

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringRequest[0]).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(mdcDuringRequest[0]);
        assertThat(MDC.get("requestId")).as("MDC is cleared after the request").isNull();
    }

    @Test
    void reusesTheClientSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
    }

    @Test
    void rejectsAnAbsurdlyLongClientIdAndGeneratesOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).hasSize(36);
    }
}
