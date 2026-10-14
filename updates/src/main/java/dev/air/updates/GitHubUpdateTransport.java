package dev.air.updates;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;

/** No GitHub credentials, cookies, browser sessions, or arbitrary download hosts. */
public final class GitHubUpdateTransport implements UpdateTransport {
    public static boolean isAllowedUrl(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) return false;
        String host = uri.getHost();
        if (host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")) return true;
        if (!host.equals("github.com") || uri.getRawQuery() != null) return false;
        String path = uri.getRawPath();
        return path != null && (path.equals("/imjunseong958-droid/browser_air/releases/latest/download/update.json")
                || path.matches("/imjunseong958-droid/browser_air/releases/download/air-v[1-9][0-9]{0,9}/(AIR\\.apk|update\\.json)"));
    }

    @Override public Response open(String address) throws IOException {
        URI uri;
        try { uri = URI.create(address); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid update address", invalid); }
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (!isAllowedUrl(uri)) throw new IOException("Untrusted update address");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "AIR-Updater");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() > 8192) throw new IOException("Invalid update redirect");
                    try { uri = uri.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new IOException("Invalid update redirect", invalid); }
                    connection.disconnect();
                    continue;
                }
                if (status != 200) throw new HttpFailure(status);
                InputStream input = connection.getInputStream();
                long length = connection.getContentLengthLong();
                return new Response() {
                    @Override public InputStream body() { return input; }
                    @Override public long length() { return length; }
                    @Override public void close() throws IOException {
                        try { input.close(); } finally { connection.disconnect(); }
                    }
                };
            } catch (IOException error) {
                connection.disconnect();
                throw error;
            }
        }
        throw new IOException("Too many update redirects");
    }
}
