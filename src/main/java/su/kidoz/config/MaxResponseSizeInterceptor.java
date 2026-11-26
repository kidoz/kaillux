package su.kidoz.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Refuses a response body larger than a fixed budget.
 *
 * <p>
 * The master-list endpoints are external and may be reached through a hostile
 * proxy or DNS answer. Buffering an unbounded body would let it consume the
 * server's heap even after the read timeout.
 */
public final class MaxResponseSizeInterceptor implements ClientHttpRequestInterceptor {

    private final int maxBytes;

    /**
     * Creates an interceptor.
     *
     * @param maxBytes
     *            the largest response body accepted
     */
    public MaxResponseSizeInterceptor(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be >= 1");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        long declaredLength = response.getHeaders().getContentLength();
        if (declaredLength > maxBytes) {
            response.close();
            throw new IOException("Response body is too large: " + declaredLength + " bytes");
        }
        return new LimitedResponse(response);
    }

    private final class LimitedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;

        private LimitedResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new LimitedInputStream(delegate.getBody(), maxBytes);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final int maxBytes;
        private int readBytes;

        private LimitedInputStream(InputStream in, int maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        private void account(int count) throws IOException {
            readBytes += count;
            if (readBytes > maxBytes) {
                throw new IOException("Response body exceeds " + maxBytes + " bytes");
            }
        }
    }
}
