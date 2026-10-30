package io.moli.android;

/** One foreground search; implementations must release every listener and socket on close. */
interface DiscoverySearch extends AutoCloseable {
    enum End { COMPLETE, CANCELLED, NETWORK_CHANGED, UNAVAILABLE }
    interface Listener {
        void found(NetworkEvidence.Entry entry);
        void warning();
        void finished(End reason);
    }
    void start(Listener listener);
    void close();
}
