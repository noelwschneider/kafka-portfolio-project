package com.orderfulfillment.monolith.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        UUID correlationId;
        try {
            correlationId = incoming != null ? UUID.fromString(incoming) : UUID.randomUUID();
        } catch (IllegalArgumentException ex) {
            correlationId = UUID.randomUUID();
        }
        CorrelationIdHolder.set(correlationId);
        MDC.put(MDC_KEY, correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationIdHolder.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
