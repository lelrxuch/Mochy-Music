#!/usr/bin/env python3
# Mochy's MPRIS face on D-Bus (https://specifications.freedesktop.org/mpris-spec/latest/), so GNOME's
# media controls and the keyboard's media keys drive Mochy's own queue. Mochy starts this and talks
# to it in JSON lines: the player's state comes in on stdin, what the desktop asks for goes out on
# stdout. It quits when Mochy does (stdin closes). Uses only GLib's Gio, which Fedora ships with GNOME.
import json
import re
import sys
import threading
import time

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

NAME = "org.mpris.MediaPlayer2.mochy"
PATH = "/org/mpris/MediaPlayer2"
ROOT = "org.mpris.MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"

XML = """
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="Identity" type="s" access="read"/>
    <property name="DesktopEntry" type="s" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek"><arg direction="in" name="Offset" type="x"/></method>
    <method name="SetPosition">
      <arg direction="in" name="TrackId" type="o"/>
      <arg direction="in" name="Position" type="x"/>
    </method>
    <method name="OpenUri"><arg direction="in" name="Uri" type="s"/></method>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
  </interface>
</node>
"""

NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

state = {"status": "Stopped"}
position_at = (0.0, time.monotonic())  # last known position in seconds, and when it was known
connection = None


def send(command, **args):
    print(json.dumps({"cmd": command, **args}), flush=True)


def track_path(video_id):
    return "/org/mpris/MediaPlayer2/track/" + re.sub(r"[^A-Za-z0-9_]", "_", video_id) if video_id else NO_TRACK


def micros(seconds):
    return int(round((seconds or 0) * 1_000_000))


def position():
    seconds, at = position_at
    if state.get("status") == "Playing":
        seconds += time.monotonic() - at
    length = state.get("length") or 0
    return min(seconds, length) if length > 0 else seconds


def metadata(s):
    if not s.get("id"):
        return {"mpris:trackid": GLib.Variant("o", NO_TRACK)}
    data = {
        "mpris:trackid": GLib.Variant("o", track_path(s["id"])),
        "xesam:title": GLib.Variant("s", s.get("title") or ""),
        "xesam:artist": GLib.Variant("as", s.get("artists") or []),
        "xesam:url": GLib.Variant("s", "https://music.youtube.com/watch?v=" + s["id"]),
    }
    if s.get("length"):
        data["mpris:length"] = GLib.Variant("x", micros(s["length"]))
    if s.get("album"):
        data["xesam:album"] = GLib.Variant("s", s["album"])
    if s.get("art"):
        data["mpris:artUrl"] = GLib.Variant("s", s["art"])
    return data


def root_property(name):
    return {
        "CanQuit": GLib.Variant("b", True),
        "CanRaise": GLib.Variant("b", True),
        "HasTrackList": GLib.Variant("b", False),
        "Identity": GLib.Variant("s", "Mochy"),
        "DesktopEntry": GLib.Variant("s", "mochy-desktop"),
        "SupportedUriSchemes": GLib.Variant("as", []),
        "SupportedMimeTypes": GLib.Variant("as", []),
    }.get(name)


def player_properties(s):
    has_track = bool(s.get("id"))
    return {
        "PlaybackStatus": GLib.Variant("s", s.get("status", "Stopped")),
        "LoopStatus": GLib.Variant("s", s.get("loop", "None")),
        "Rate": GLib.Variant("d", 1.0),
        "Shuffle": GLib.Variant("b", bool(s.get("shuffle"))),
        "Metadata": GLib.Variant("a{sv}", metadata(s)),
        "Volume": GLib.Variant("d", float(s.get("volume", 1.0))),
        "MinimumRate": GLib.Variant("d", 1.0),
        "MaximumRate": GLib.Variant("d", 1.0),
        "CanGoNext": GLib.Variant("b", bool(s.get("canNext"))),
        "CanGoPrevious": GLib.Variant("b", has_track),
        "CanPlay": GLib.Variant("b", has_track),
        "CanPause": GLib.Variant("b", has_track),
        "CanSeek": GLib.Variant("b", has_track and (s.get("length") or 0) > 0),
        "CanControl": GLib.Variant("b", True),
    }


def get_property(_conn, _sender, _path, interface, name):
    if interface == ROOT:
        return root_property(name)
    if name == "Position":
        return GLib.Variant("x", micros(position()))
    return player_properties(state).get(name)


def set_property(_conn, _sender, _path, interface, name, value):
    if interface != PLAYER:
        return False
    if name == "Shuffle":
        send("Shuffle", value=value.unpack())
    elif name == "LoopStatus":
        send("Loop", value=value.unpack())
    elif name == "Volume":
        send("Volume", value=max(0.0, min(1.0, value.unpack())))
    else:
        return False
    return True


def method_call(_conn, _sender, _path, interface, method, params, invocation):
    args = params.unpack()
    if interface == ROOT and method in ("Raise", "Quit"):
        send(method)
    elif method in ("Next", "Previous", "Pause", "PlayPause", "Stop", "Play"):
        send(method)
    elif method == "Seek":
        send("SetPosition", position=max(0.0, position() + args[0] / 1_000_000))
    elif method == "SetPosition":
        # the spec ignores a position for a track that's no longer playing
        if args[0] == track_path(state.get("id")):
            send("SetPosition", position=args[1] / 1_000_000)
    elif method == "OpenUri":
        pass
    else:
        invocation.return_dbus_error("org.freedesktop.DBus.Error.UnknownMethod", method)
        return
    invocation.return_value(None)


def update(new):
    """takes Mochy's latest state and tells the desktop what changed"""
    global state, position_at
    before = player_properties(state)
    seeked = new.pop("seeked", False)
    state = new
    position_at = (new.get("position") or 0.0, time.monotonic())
    after = player_properties(state)
    changed = {k: v for k, v in after.items() if before.get(k) is None or not before[k].equal(v)}
    if changed:
        connection.emit_signal(None, PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                               GLib.Variant("(sa{sv}as)", (PLAYER, changed, [])))
    if seeked:
        connection.emit_signal(None, PATH, PLAYER, "Seeked", GLib.Variant("(x)", (micros(position()),)))
    return False


def read_stdin(loop):
    for line in sys.stdin:
        try:
            GLib.idle_add(update, json.loads(line))
        except ValueError:
            continue
    GLib.idle_add(loop.quit)


def main():
    global connection
    loop = GLib.MainLoop()
    connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    node = Gio.DBusNodeInfo.new_for_xml(XML)
    for interface in node.interfaces:
        connection.register_object(PATH, interface, method_call, get_property, set_property)
    Gio.bus_own_name_on_connection(connection, NAME, Gio.BusNameOwnerFlags.NONE, None, None)
    threading.Thread(target=read_stdin, args=(loop,), daemon=True).start()
    loop.run()


if __name__ == "__main__":
    main()
