package com.mtperaya.transactionbackend.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    @Test
    @DisplayName("AT-8: Requests exceeding rate limit receive HTTP 429 and Retry-After header")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        int limit = 5;
        RateLimitingFilter filter = new RateLimitingFilter(true, limit, limit);

        // First 5 requests succeed (HTTP 200)
        for (int i = 0; i < limit; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
            request.setRemoteAddr("192.168.1.100");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        // 6th request is throttled
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
        request.setRemoteAddr("192.168.1.100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    @DisplayName("Different client IPs have independent rate limit quotas")
    void shouldIsolateQuotasByClientIp() throws Exception {
        int limit = 2;
        RateLimitingFilter filter = new RateLimitingFilter(true, limit, limit);

        // Exhaust IP 1
        for (int i = 0; i < limit; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/status");
            req.setRemoteAddr("10.0.0.1");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest exhaustedReq = new MockHttpServletRequest("GET", "/status");
        exhaustedReq.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse exhaustedResp = new MockHttpServletResponse();
        filter.doFilter(exhaustedReq, exhaustedResp, new MockFilterChain());
        assertThat(exhaustedResp.getStatus()).isEqualTo(429);

        // IP 2 is still fresh
        MockHttpServletRequest freshReq = new MockHttpServletRequest("GET", "/status");
        freshReq.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse freshResp = new MockHttpServletResponse();
        filter.doFilter(freshReq, freshResp, new MockFilterChain());
        assertThat(freshResp.getStatus()).isEqualTo(200);
    }
}
