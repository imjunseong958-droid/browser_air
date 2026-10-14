package dev.air.updates;

import java.io.IOException;
import java.io.InputStream;

public interface UpdateTransport {
    Response open(String url) throws IOException;

    interface Response extends AutoCloseable {
        InputStream body();
        long length();
        @Override void close() throws IOException;
    }

    final class HttpFailure extends IOException {
        public final int status;
        public HttpFailure(int status) { super("Update HTTP status " + status); this.status = status; }
    }
}
