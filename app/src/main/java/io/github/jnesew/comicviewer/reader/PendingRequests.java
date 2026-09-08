package io.github.jnesew.comicviewer.reader;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/** Identity, not URI alone, decides which completion may remove a pending request. */
final class PendingRequests {
    static final class Request {
        private Future<?> future;
        private boolean cancelled;
        synchronized void attach(Future<?> future) {
            if (cancelled) future.cancel(true);
            else this.future = future;
        }
        synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(true);
        }
    }
    private final Map<String, Request> requests = new HashMap<>();
    synchronized boolean contains(String key) { return requests.containsKey(key); }
    synchronized Request begin(String key) {
        Request request = new Request();
        Request previous = requests.put(key, request);
        if (previous != null) previous.cancel();
        return request;
    }
    synchronized boolean complete(String key, Request request) {
        return requests.remove(key, request);
    }
    synchronized void cancelAll() {
        for (Request request : requests.values()) request.cancel();
        requests.clear();
    }
}
