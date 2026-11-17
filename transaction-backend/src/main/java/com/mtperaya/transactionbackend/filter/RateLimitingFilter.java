package com.mtperaya.transactionbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final int publicRpm;
    private final int transferRpm;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitingFilter(
            @Value("${app.ratelimit.enabled:true}") boolean enabled,
            @Value("${app.ratelimit.public-rpm:100}") int publicRpm,
            @Value("${app.ratelimit.transfer-rpm:20}") int transferRpm) {
        this.enabled = enabled;
        this.publicRpm = publicRpm;
        this.transferRpm = transferRpm;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Check if rate limiting applies
        boolean isTransfer = "POST".equalsIgnoreCase(method) && path.startsWith("/transactions");
        int limit = isTransfer ? transferRpm : publicRpm;

        String clientKey = resolveClientKey(request, isTransfer);
        TokenBucket bucket = buckets.computeIfAbsent(clientKey, k -> new TokenBucket(limit));

        if (!bucket.tryConsume()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write("""
                {
                  "status": 429,
                  "error": "Too Many Requests",
                  "message": "Rate limit exceeded. Please try again later."
                }
                """);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request, boolean isTransfer) {
        if (isTransfer) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                return "user:" + authHeader.hashCode();
            }
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return "ip:" + ip;
    }

    public void clear() {
        buckets.clear();
    }

    static class TokenBucket {
        private final int capacity;
        private final AtomicInteger tokens;
        private final AtomicLong lastRefillTime;

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = new AtomicInteger(capacity);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime.get();
            if (elapsed >= 60_000L) {
                tokens.set(capacity);
                lastRefillTime.set(now);
            }
        }
    }
}
