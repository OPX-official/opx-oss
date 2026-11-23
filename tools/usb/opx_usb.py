#!/usr/bin/env python3
"""Client for the OPX OSS USB host bridge (the in-app replacement for the API app).

The app owns one abstract Unix socket (``opx.usb``). This module speaks its tiny protocol
and receives USB file descriptors over ``SCM_RIGHTS``:

    -> {"cmd": "list"}                              <- {"ok": true, "devices": [...]}
    -> {"cmd": "open", "path": "/dev/bus/usb/1/2"}  <- {"ok": true} + the fd as ancillary data

The descriptor travels with the *first* byte of the reply (that is how
``LocalSocket#setFileDescriptorsForSend`` works), so the
reply must be read with ``recv_fds`` — a plain ``recv()`` would consume the bytes and drop
the descriptor with them.
"""

import json
import os
import socket
import sys

SOCKET_NAME = "\0opx.usb"
MAX_REPLY = 4096


def _connect(timeout=120.0):
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    sock.connect(SOCKET_NAME)
    return sock


def _read_line(sock, buffer):
    while not buffer.endswith(b"\n"):
        chunk = sock.recv(MAX_REPLY)
        if not chunk:
            break
        buffer += chunk
    return buffer


def _exchange(request):
    with _connect() as sock:
        sock.sendall((json.dumps(request) + "\n").encode("utf-8"))
        response = json.loads(_read_line(sock, b"").decode("utf-8") or "{}")
        return sock, response


def list_devices():
    sock, response = _exchange({"cmd": "list"})
    sock.close()
    if not response.get("ok"):
        raise RuntimeError(response.get("error") or "the USB bridge refused the request")
    return response.get("devices") or []


def open_device(path):
    """Ask the app for ``path``; returns the received file descriptor."""
    sock = _connect()
    try:
        sock.sendall((json.dumps({"cmd": "open", "path": path}) + "\n").encode("utf-8"))
        # One call for the reply and its descriptor: the fd rides on the first segment.
        data, descriptors, _flags, _address = socket.recv_fds(sock, MAX_REPLY, 1)
        buffer = _read_line(sock, data)
        response = json.loads(buffer.decode("utf-8") or "{}")
        if not response.get("ok"):
            raise RuntimeError(response.get("error") or "the USB bridge could not open the device")
        if not descriptors:
            raise RuntimeError("the USB bridge returned no descriptor")
        fd = descriptors[0]
    finally:
        sock.close()
    os.set_inheritable(fd, True)
    return fd


if __name__ == "__main__":
    print(json.dumps(list_devices()))
