package io.moli.android;

final class NativeServer {
    static { System.loadLibrary("moli_android"); }
    private NativeServer() {}
    static native int start(String data, String key, String token, String pin);
    static native void stop();
}
