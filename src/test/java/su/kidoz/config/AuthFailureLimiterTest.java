package su.kidoz.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

@DisplayName("AuthFailureLimiter Tests")
class AuthFailureLimiterTest {

    private static final int MAX_FAILURES = 3;

    private static final long WINDOW_MILLIS = 60_000;

    /** Stands in for the rest of the chain and answers with a chosen status. */
    private static final class RestOfChain implements FilterChain {
        private int calls;
        private int status = 401;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            calls++;
            ((MockHttpServletResponse) response).setStatus(status);
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000);

    private final RestOfChain chain = new RestOfChain();

    private AuthFailureLimiter limiter;

    @BeforeEach
    void createLimiter() {
        limiter = new AuthFailureLimiter(MAX_FAILURES, WINDOW_MILLIS, now::get);
    }

    private MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private MockHttpServletResponse pass(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        limiter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a request without credentials is passed through: it costs nothing to refuse")
    void aRequestWithoutCredentialsIsPassedThrough() throws Exception {
        chain.status = 200;

        assertEquals(200, pass(request(null)).getStatus());
        assertEquals(200, pass(request("Bearer something")).getStatus());
        assertEquals(2, chain.calls);
    }

    @Test
    @DisplayName("a request with credentials is passed through while the address is not locked")
    void aRequestWithCredentialsIsPassedThroughWhileNotLocked() throws Exception {
        chain.status = 200;

        assertEquals(200, pass(request("Basic abc")).getStatus());
        assertEquals(1, chain.calls);
    }

    @Test
    @DisplayName("an address that keeps failing is refused without reaching the chain")
    void anAddressThatKeepsFailingIsRefusedWithoutReachingTheChain() throws Exception {
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, pass(request("Basic abc")).getStatus());
        }
        assertEquals(MAX_FAILURES, chain.calls);

        // The password is right this time, but the address is locked.
        chain.status = 200;
        assertEquals(401, pass(request("Basic abc")).getStatus());
        assertEquals(MAX_FAILURES, chain.calls, "the password check must be skipped");
    }

    @Test
    @DisplayName("a trusted proxy buckets by the forwarded client address")
    void aTrustedProxyBucketsByForwardedAddress() throws Exception {
        limiter = new AuthFailureLimiter(MAX_FAILURES, WINDOW_MILLIS, 10, Set.of("127.0.0.1"),
                now::get);

        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("127.0.0.1");
        first.addHeader("Authorization", "Basic abc");
        first.addHeader("X-Forwarded-For", "203.0.113.50");
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, pass(first).getStatus());
        }

        chain.status = 200;
        assertEquals(401, pass(first).getStatus(), "the forwarded client is locked");

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("127.0.0.1");
        second.addHeader("Authorization", "Basic abc");
        second.addHeader("X-Forwarded-For", "203.0.113.51");
        assertEquals(200, pass(second).getStatus(), "another client behind the same proxy passes");
    }

    @Test
    @DisplayName("a full table evicts the oldest address instead of stopping the limit")
    void aFullTableEvictsTheOldestAddress() throws Exception {
        limiter = new AuthFailureLimiter(MAX_FAILURES, WINDOW_MILLIS, 1, Set.of(), now::get);

        assertEquals(401, pass(request("Basic abc")).getStatus());

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("203.0.113.8");
        second.addHeader("Authorization", "Basic abc");
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, pass(second).getStatus());
        }

        chain.status = 200;
        assertEquals(401, pass(second).getStatus(),
                "a new address must still be limited when the table is full");
    }

    @Test
    @DisplayName("one address failing does not lock another")
    void oneAddressFailingDoesNotLockAnother() throws Exception {
        for (int i = 0; i < MAX_FAILURES; i++) {
            assertEquals(401, pass(request("Basic abc")).getStatus());
        }

        MockHttpServletRequest other = request("Basic abc");
        other.setRemoteAddr("203.0.113.8");
        chain.status = 200;

        assertEquals(200, pass(other).getStatus());
    }

    @Test
    @DisplayName("a success clears the earlier failures of the address")
    void aSuccessClearsTheEarlierFailures() throws Exception {
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            pass(request("Basic abc"));
        }
        chain.status = 200;
        pass(request("Basic abc"));
        chain.status = 401;
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            assertEquals(401, pass(request("Basic abc")).getStatus());
        }

        // Still one failure short of the limit.
        assertEquals(MAX_FAILURES * 2 - 1, chain.calls);
    }

    @Test
    @DisplayName("the lock ends when the window has passed")
    void theLockEndsWhenTheWindowHasPassed() throws Exception {
        for (int i = 0; i < MAX_FAILURES; i++) {
            pass(request("Basic abc"));
        }
        assertEquals(401, pass(request("Basic abc")).getStatus());

        now.addAndGet(WINDOW_MILLIS);
        chain.status = 200;

        assertEquals(200, pass(request("Basic abc")).getStatus());
    }

    @Test
    @DisplayName("failures older than the window are not counted")
    void failuresOlderThanTheWindowAreNotCounted() throws Exception {
        pass(request("Basic abc"));
        now.addAndGet(WINDOW_MILLIS);
        for (int i = 0; i < MAX_FAILURES - 1; i++) {
            pass(request("Basic abc"));
        }

        // The first failure has expired, so the address is one short of the limit.
        assertEquals(MAX_FAILURES, chain.calls);
        assertTrue(pass(request("Basic abc")).getStatus() == 401);
        assertEquals(MAX_FAILURES + 1, chain.calls, "this one still reached the chain");
    }

    @Test
    @DisplayName("an IPv6 address is counted by its /64 network")
    void anIpv6AddressIsCountedByItsNetwork() {
        assertEquals("2001:db8:1:2/64", AuthFailureLimiter.bucketKey("2001:db8:1:2:3:4:5:6"));
        assertEquals("2001:db8:1:2/64", AuthFailureLimiter.bucketKey("2001:db8:1:2:ffff::1"));
        assertEquals("::", AuthFailureLimiter.bucketKey("::"));
    }

    @Test
    @DisplayName("an IPv4 address, mapped or not, is counted by itself")
    void anIpv4AddressIsCountedByItself() {
        assertEquals("203.0.113.7", AuthFailureLimiter.bucketKey("203.0.113.7"));
        assertEquals("203.0.113.7", AuthFailureLimiter.bucketKey("::ffff:203.0.113.7"));
        assertEquals("unknown", AuthFailureLimiter.bucketKey(null));
    }
}
