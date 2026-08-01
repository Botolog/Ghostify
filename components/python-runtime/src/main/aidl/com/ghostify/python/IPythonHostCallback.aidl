// Callback sink delivered to the app process when Python pushes an event
// (e.g. spotdl progress hooks). Payload is a JSON string; oneway so the host
// never blocks on a slow consumer.
package com.ghostify.python;

interface IPythonHostCallback {
    oneway void onEvent(in String jsonEvent);
}
