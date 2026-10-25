package dafeiyu.util;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class HttpCalls {

    public static final class Shared {
        private final Supplier<HttpClient> factory;
        private volatile HttpClient client;

        public Shared(Supplier<HttpClient> factory) {
            this.factory = factory;
            this.client = factory.get();
        }

        public HttpClient get() {
            return client;
        }

        public boolean renewIfDead(HttpClient used, Throwable error) {
            if (!dead(error)) {
                return false;
            }
            synchronized (this) {
                if (client == used) {
                    client = factory.get();
                    used.shutdown();
                }
            }
            return true;
        }
    }

    private HttpCalls() {}

    public static boolean dead(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            String message = e.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("selector manager closed")
                    || e instanceof IOException && message.equals("closed"))) {
                return true;
            }
        }
        return false;
    }

    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> body, Duration total)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> call = client.sendAsync(request, body);
        try {
            return call.get(total.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new HttpTimeoutException("no complete response within " + total.toSeconds() + " s");
        } catch (InterruptedException e) {
            call.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
    }
}
