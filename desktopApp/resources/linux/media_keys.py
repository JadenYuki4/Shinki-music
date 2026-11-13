# SPDX-License-Identifier: GPL-3.0-or-later
"""Expose play/pause through the user's MPRIS session bus. GIO comes from python3-gi."""
import sys
from gi.repository import Gio, GLib

ROOT = "org.mpris.MediaPlayer2"
PLAYER = ROOT + ".Player"
PATH = "/org/mpris/MediaPlayer2"
properties = {
    ROOT: {"Identity": ("s", "Orbit Music"), "CanQuit": ("b", False),
           "CanRaise": ("b", False), "HasTrackList": ("b", False),
           "SupportedUriSchemes": ("as", []), "SupportedMimeTypes": ("as", [])},
    PLAYER: {"PlaybackStatus": ("s", "Stopped"), "CanControl": ("b", True),
             "CanPlay": ("b", True), "CanPause": ("b", True), "CanSeek": ("b", False),
             "CanGoNext": ("b", True), "CanGoPrevious": ("b", True),
             "Metadata": ("a{sv}", {}), "Volume": ("d", 1.0), "Rate": ("d", 1.0),
             "MinimumRate": ("d", 1.0), "MaximumRate": ("d", 1.0), "Position": ("x", 0)}
}
xml = "<node>"
for interface, props in properties.items():
    xml += f'<interface name="{interface}">'
    if interface == PLAYER:
        xml += "".join(f'<method name="{m}"/>' for m in ("Play", "Pause", "PlayPause", "Next", "Previous"))
    for key, (typ, _) in props.items():
        xml += f'<property name="{key}" type="{typ}" access="read"/>'
    xml += "</interface>"
node = Gio.DBusNodeInfo.new_for_xml(xml + "</node>")
bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
loop = GLib.MainLoop()

def call(conn, sender, path, interface, method, args, request):
    print(method, flush=True)
    request.return_value(GLib.Variant("()", ()))

def get(conn, sender, path, interface, key):
    return GLib.Variant(*properties[interface][key])

def status(channel, event):
    value = sys.stdin.readline().strip()
    if value not in ("Playing", "Paused", "Stopped"):
        loop.quit()
        return False
    properties[PLAYER]["PlaybackStatus"] = ("s", value)
    bus.emit_signal(None, PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                    GLib.Variant("(sa{sv}as)", (PLAYER, {"PlaybackStatus": GLib.Variant("s", value)}, [])))
    return True

for info in node.interfaces:
    bus.register_object(PATH, info, call, get, None)
Gio.bus_own_name_on_connection(bus, ROOT + ".OrbitMusic", Gio.BusNameOwnerFlags.NONE, None, None)
GLib.io_add_watch(sys.stdin, GLib.IO_IN | GLib.IO_HUP, status)
loop.run()
