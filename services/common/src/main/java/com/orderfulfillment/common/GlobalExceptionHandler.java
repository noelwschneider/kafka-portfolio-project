package com.orderfulfillment.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed request body", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(HttpServletRequest request) {
        // Spring's DispatcherServlet raises this for any request path that matches no
        // @RequestMapping and no static resource. Without a dedicated handler here it falls
        // through to handleUnexpected() below and gets reported as a 500, which is wrong (and
        // was logged at ERROR for what is just a client requesting a route that doesn't exist).
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No handler for this request", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpServletRequest request) {
        // Same shape of bug as NoResourceFoundException above, found while exercising this handler
        // live for the 404 case (sprint-2 bug hunt): a request to a real path with the wrong HTTP
        // method (e.g. POST on a GET-only route) throws this, which the catch-all below was
        // reporting as a 500 INTERNAL_ERROR instead of the correct 405.
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported for this route", request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        // Sprint 2 goal 2, item 3 (docs/agent-reports/sprint-2 bug hunt named a related-but-distinct
        // defect; this is the one OrderStatusWatcher's Javadoc originally documented). This
        // exception means the client's connection is already unusable — most commonly a broken pipe
        // on a long-lived SSE stream (GET /api/orders/stream) whose Content-Type is already
        // committed to text/event-stream. Falling through to the catch-all below used to try to
        // write a JSON ApiError body onto that committed streaming response, which no
        // HttpMessageConverter can do — observed live as a second, uncaught
        // HttpMessageNotWritableException ("No converter for [ApiError] with preset Content-Type
        // 'text/event-stream'") logged by Spring's own ExceptionHandlerExceptionResolver on top of
        // the original broken-pipe failure. A void return here tells Spring MVC the response has
        // already been fully handled — it makes no attempt to write anything at all, so there is
        // nothing left to fail. Logged at DEBUG: a client disconnecting mid-stream is an expected,
        // routine event, not a server error.
        log.debug("Dropping unusable async request for {} {} — client connection is already gone",
                request.getMethod(), request.getRequestURI(), ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Phase 9: this handler previously discarded the exception entirely — a 500 left zero
        // trace anywhere, in any service's logs, which defeats the whole point of correlation-id
        // tracing. correlationId is already in MDC (CorrelationIdFilter), so ECS structured
        // logging attaches it automatically; the same value is also in the ApiError response body.
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                CorrelationIdHolder.get()
        );
        return ResponseEntity.status(status).body(body);
    }
}
