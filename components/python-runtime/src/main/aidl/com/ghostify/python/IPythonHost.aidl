// Interface between the app process and the :pythond process that owns the
// single Chaquopy interpreter.
//
// Requests and responses are opaque JSON envelopes (see HostProtocol.kt).
// The host executes "call" on a single-thread executor, so the interpreter is
// always touched by exactly one thread at a time (TEST_PLAN T-029).
package com.ghostify.python;

interface IPythonHost {
    /**
     * Execute a bridge request and return a JSON response envelope.
     * May take a long time (downloads); the client applies its own timeout.
     */
    String call(in String requestJson);

    /** Register/unregister a sink for Python-pushed events (progress hooks). */
    void registerCallback(IPythonHostCallback callback);
    void unregisterCallback(IPythonHostCallback callback);

    /** Bring the host process to the foreground to survive app backgrounding. */
    void enterForeground(in int notificationId, in String channelId,
                         in String title, in String text);
    void leaveForeground();
}
