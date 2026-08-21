package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Bug hunt (sprint-2 goal 7): a request to a path with no matching {@code @RequestMapping} and no
 * static resource used to come back as a 500. Spring's {@code DispatcherServlet} raises
 * {@code NoResourceFoundException} for exactly this case, and {@link
 * com.orderfulfillment.common.GlobalExceptionHandler}'s catch-all {@code Exception.class} handler
 * was swallowing it before Spring's own default 404 handling ever got a chance to run — every
 * unmapped route was reported as {@code INTERNAL_ERROR} and logged at ERROR, which is both a wrong
 * status code for a routine client mistake and log noise that could mask a real failure. See
 * docs/agent-reports/sprint-2/bug-hunt.md.
 */
class UnmappedRouteIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unmappedPathReturns404NotInternalServerError() {
        client.get().uri("/this-path-does-not-exist")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"code\":\"NOT_FOUND\""));
    }

    /**
     * Same bug, different Spring-internal exception: found live while exercising the 404 case above
     * against scenario-service ({@code POST /demo/scenario-runs}, a GET-only route) — the catch-all
     * handler was reporting {@code HttpRequestMethodNotSupportedException} as a 500 too. Order
     * Service's {@code GET /api/orders/stream} is a real GET-only route in this service, so POSTing
     * to it reproduces the same defect here rather than only in scenario-service.
     */
    @Test
    void wrongHttpMethodOnRealRouteReturns405NotInternalServerError() {
        client.post().uri("/api/orders/stream")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"code\":\"METHOD_NOT_ALLOWED\""));
    }
}
