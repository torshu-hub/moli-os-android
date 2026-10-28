package io.moli.android;
final class NativeServer {
    static { System.loadLibrary("moli_android"); }
    static native int start(String data, String master, String token, String pin);
    static native void stop();
    static native String configuration(String input);
    private NativeServer() {}
}
