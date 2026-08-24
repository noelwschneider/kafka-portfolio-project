# CORS

*Referenced from [Chapter 2.3 — The HTTP layer](../../02-domain/3-the-http-layer.md).*

---

## The rule being relaxed

Browsers enforce the **same-origin policy**: JavaScript on one origin cannot read responses from
another. An *origin* is scheme + host + port, so `http://localhost:5173` and `http://localhost:8081`
are different origins — same host, different port is enough.

This is not a server-side security control. It is a browser control protecting *users*: without it,
any page you visit could issue authenticated requests to your bank with your cookies attached and read
the results. `curl` is unaffected, which is why an endpoint can work perfectly from a terminal and be
blocked in a browser.

**CORS** (Cross-Origin Resource Sharing) is how a server opts in to being read cross-origin.

## How it works

The server returns headers saying who may read the response:

```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET, POST
Access-Control-Allow-Headers: Content-Type, X-Correlation-Id
```

For anything beyond a simple `GET` or form-shaped `POST`, the browser first sends a **preflight**
`OPTIONS` request asking whether the real request is allowed. Only on an affirmative answer does it
send the real one. A misconfigured preflight is the usual cause of "it works in Postman."

Note what the browser is blocking: it blocks *your JavaScript from reading the response*. In the
non-preflighted cases the request may well have reached the server and had its effect. CORS is not
authorization.

## In Spring

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST");
    }
}
```

Driven by configuration, not hard-coded:

```yaml
app:
  cors:
    allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}
```

The local default works out of the box; a deployment overrides it with an environment variable and no
code change.

## Three ways to get it wrong

### `*` with credentials

`Access-Control-Allow-Origin: *` combined with `allowCredentials(true)` is rejected by browsers, and
for good reason — it would let any site make credentialed requests on a user's behalf. Enumerate
origins, or use patterns. Never `*` on anything non-public.

### Assuming one CORS configuration covers everything

Spring MVC serves different things through different handler mappings. `WebMvcConfigurer`'s
`addCorsMappings` covers regular `@RestController` endpoints — but **Actuator endpoints are served by a
separate `WebMvcEndpointHandlerMapping` that does not go through it**, and need their own:

```yaml
management:
  endpoints:
    web:
      cors:
        allowed-origin-patterns: "${app.cors.allowed-origin-patterns}"
        allowed-methods: GET
```

This is a genuinely easy one to miss, because the endpoint works perfectly under `curl` and fails only
in the browser. Verifying in a real browser rather than a terminal is what catches it.

### Reaching for CORS when the answer is a proxy

If the frontend is served from the same origin as the API — a reverse proxy routing `/api` to the
backend, or an ingress putting both behind one hostname — there is no cross-origin request and no CORS
configuration needed at all.

That is usually the better production setup. CORS then exists only for local development, where the
Vite dev server and the backend genuinely are on different ports.
