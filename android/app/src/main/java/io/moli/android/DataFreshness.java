package io.moli.android;
/** Receipt freshness, not a claim about a provider's observation time. */
final class DataFreshness {
    static boolean recent(boolean running, boolean online, boolean hasValue, long stamp, long now, long threshold) {
        return running && online && hasValue && stamp > 0 && now >= stamp && now-stamp <= threshold;
    }
}
