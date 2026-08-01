"""ghostify_dl -- Ghostify Python bridge module.

Lives in Chaquopy's ``src/main/python`` directory and is imported by the
``:pythond`` host process (see ``PythondService`` / ``PythonHost``).

Design notes
------------
* ``dispatch`` is the single entry point used by the Kotlin bridge. Arguments
  and results are marshaled as JSON, keeping all type conversion in one place
  (Python's ``json``) so ``PyObject`` never has to touch the wire protocol.
* ``spotdl`` is imported lazily and cached: interpreter boot stays fast and a
  broken spotdl install never prevents the app from booting (T-026).
* ``prepend_path`` extends ``os.environ["PATH"]`` so the bundled static
  ``ffmpeg`` (see ``FfmpegLocator``) is found by the ``subprocess`` calls
  spotdl makes (T-025).
"""

import json
import os
import threading
import time

_MODULES = {}
_SERIAL = {"seq": 0}


def _import(name):
    """Import *name* once and cache the module object."""
    module = _MODULES.get(name)
    if module is None:
        module = __import__(name, fromlist=["*"])
        _MODULES[name] = module
    return module


def _json_default(value):
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def dispatch(module_name, method, args_json):
    """Run ``module.method(*args)`` and return a JSON string of the result."""
    module = _import(module_name)
    func = getattr(module, method)
    args = json.loads(args_json) if args_json else []
    result = func(*args)
    return json.dumps(result, default=_json_default, allow_nan=False)


def prepend_path(directory):
    """Prepend *directory* to ``os.environ["PATH"]`` (idempotent)."""
    directory = os.path.abspath(directory)
    current = os.environ.get("PATH", "")
    parts = [p for p in current.split(os.pathsep) if p]
    if directory not in parts:
        os.environ["PATH"] = directory + os.pathsep + current
    return {"path": os.environ["PATH"], "ffmpeg": _which_ffmpeg()}


def boot_probe():
    """Interpreter self-check used by the boot tests (T-022..T-024)."""
    import platform
    return {
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "pid": os.getpid(),
        "thread": threading.current_thread().name,
        "home": os.environ.get("HOME", ""),
        "has_ffmpeg": _which_ffmpeg() is not None,
    }


def _which_ffmpeg():
    import shutil
    return shutil.which("ffmpeg")


def import_spotdl():
    """Import spotdl; raises on failure. Cached across calls (T-026)."""
    module = _import("spotdl")
    return {
        "module": "spotdl",
        "version": getattr(module, "__version__", "unknown"),
    }


def ffmpeg_probe():
    """Run a trivial ffmpeg conversion and report the output size (T-025).

    Requires the bundled static ffmpeg to include libmp3lame (the codec spotdl
    uses for MP3 output); validate this in the Phase-0 spike.
    """
    import subprocess
    import tempfile

    ffmpeg = _which_ffmpeg()
    if ffmpeg is None:
        raise RuntimeError("ffmpeg not found on PATH")
    out_dir = tempfile.mkdtemp(prefix="ghostify_ffmpeg_")
    out_path = os.path.join(out_dir, "tone.mp3")
    command = [
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-c:a", "libmp3lame", "-b:a", "128k", out_path,
    ]
    proc = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
    )
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace") if proc.stderr else ""
        raise RuntimeError("ffmpeg probe failed rc=%d: %s" % (proc.returncode, detail))
    return {
        "converted": True,
        "bytes": os.path.getsize(out_path),
        "ffmpeg": ffmpeg,
    }


def probe_serial(tag):
    """Echo a tag plus a global sequence number (T-029 serialization test).

    Sleeps so concurrent (mis-)delivery would visibly interleave; a correct
    serialized bridge delivers each call to completion in FIFO order, so the
    sequence numbers come back unique, gapless and ordered.
    """
    time.sleep(0.05)
    _SERIAL["seq"] += 1
    return {
        "tag": tag,
        "seq": _SERIAL["seq"],
        "thread": threading.current_thread().name,
    }


def crash_probe():
    """Deliberately kill the interpreter (used by the T-028 containment test).

    Must only ever be called inside the ``:pythond`` process; the app process
    is untouched because it never hosts the interpreter.
    """
    os.abort()
