"""
utusb - USB access for the Ubuntu Terminal app. NO root needed.

Python 3 library (stdlib only) that talks to the app's USB bridges over
local TCP sockets:

    utusb.RawUsb(vid_pid)        raw USB  (control/bulk/interrupt) -> :8040
    utusb.Serial(vid_pid, baud)  serial adapters (CH340/CP210x/..)  -> :8039
    utusb.Flash(vid_pid)         FAT32 USB flash drives             -> :8041
    utusb.list_usb()             all USB devices

Quick start:

    import utusb

    # ---- raw USB (any device) - pyusb-like API
    dev = utusb.RawUsb("16c0:0486")
    print(dev.ctrl_transfer(0x80, 6, 0x0100, 0, 18))   # GET_DESCRIPTOR (device)
    dev.write(0x01, b"hello")                          # bulk OUT endpoint 1
    data = dev.read(0x81, 64)                          # bulk IN  endpoint 1
    dev.close()

    # ---- serial (Arduino / ESP32 / RPi console ...)
    ser = utusb.Serial("1a86:7523", 115200)
    ser.write(b"AT\\r\\n")
    print(ser.readline())
    ser.dtr(True)                # control lines (Arduino auto-reset)
    ser.close()

    # ---- flash drive (FAT32)
    fl = utusb.Flash("0951:1666")
    print(fl.ls("/"))
    print(fl.cat("/notes.txt"))
    fl.get("/photo.jpg", "photo.jpg")      # download
    fl.put("backup.tar.gz", "/backup.tar.gz")  # upload
    fl.close()

Tip: to use standard serial tools (screen/minicom/avrdude) install socat and
map the relay to a real tty:
    apt install socat
    socat PTY,raw,echo=0,link=$HOME/ttyUSB0 TCP:127.0.0.1:8039 &
    avrdude -P $HOME/ttyUSB0 ...   (while: dapi usbser open 1a86:7523)
"""

import socket

_HOST = "127.0.0.1"
_CTRL = 8037   # dapi request/response bridge
_RAW = 8040    # raw USB line protocol
_SERIAL = 8039 # serial relay
_FLASH = 8041  # flash transfer stream


def _bridge(cmd, timeout=10.0):
    """One request -> full response round trip with the 8037 bridge."""
    s = socket.create_connection((_HOST, _CTRL), timeout=timeout)
    try:
        s.settimeout(timeout)
        s.sendall(cmd.encode("utf-8") + b"\n")
        out = b""
        while True:
            d = s.recv(65536)
            if not d:
                break
            out += d
        return out
    finally:
        s.close()


def _need_ok(resp, what):
    txt = resp.decode("utf-8", "replace")
    if txt.startswith("OK") or txt.startswith("closed") or txt.startswith("no "):
        return txt
    if "permission" in txt:
        raise PermissionError(
            "USB permission needed - allow the dialog on the phone, then retry")
    raise RuntimeError(what + " failed: " + txt.strip())


def list_usb():
    """All USB devices visible to the app (like `dapi usb`)."""
    return _bridge("usb").decode("utf-8", "replace")


def list_raw():
    """USB devices with interfaces (like `dapi usbraw list`)."""
    return _bridge("usbraw\tlist").decode("utf-8", "replace")


def list_flash():
    """Mass storage devices (like `dapi flash list`)."""
    return _bridge("flash\tlist").decode("utf-8", "replace")


class RawUsb:
    """Raw USB device - control/bulk/interrupt transfers (pyusb-like)."""

    def __init__(self, vid_pid):
        _need_ok(_bridge("usbraw\topen\t" + vid_pid), "usbraw open " + vid_pid)
        self.vid_pid = vid_pid
        self.s = socket.create_connection((_HOST, _RAW), timeout=5)

    def _cmd(self, line, timeout=5.0):
        self.s.settimeout(timeout)
        self.s.sendall((line + "\n").encode("ascii"))
        out = b""
        while not out.endswith(b"\n"):
            d = self.s.recv(65536)
            if not d:
                raise RuntimeError("raw bridge closed")
            out += d
        return out.decode("utf-8", "replace").strip()

    def endpoints(self):
        """List endpoints: ['EP 0x81 IN  bulk maxPacket=64', ...]"""
        return self._cmd("I").split("\n")

    def ctrl_transfer(self, bmRequestType, bRequest, wValue, wIndex,
                      data_or_length, timeout=3000):
        """pyusb-style control transfer.
        IN  (bit7 set):   ctrl_transfer(0x80, 6, 0x0100, 0, 18) -> bytes
        OUT:              ctrl_transfer(0x00, 9, 1, 0, b"")     -> sent count
        """
        if bmRequestType & 0x80:
            r = self._cmd("C %02x %02x %04x %04x %d" % (
                bmRequestType, bRequest, wValue, wIndex, data_or_length),
                timeout / 1000.0 + 1)
            if r.startswith("D "):
                return bytes.fromhex(r[2:])
            if r == "D":
                return b""
            raise RuntimeError("ctrl_transfer: " + r)
        data = bytes(data_or_length) if data_or_length else b""
        r = self._cmd("C %02x %02x %04x %04x %d %s" % (
            bmRequestType, bRequest, wValue, wIndex, len(data), data.hex()),
            timeout / 1000.0 + 1)
        if r.startswith("OK"):
            return int(r.split()[1])
        raise RuntimeError("ctrl_transfer: " + r)

    def write(self, endpoint, data, timeout=3000):
        """Bulk/interrupt OUT write to an endpoint address (e.g. 0x01)."""
        r = self._cmd("W %02x %s" % (endpoint, bytes(data).hex()),
                      timeout / 1000.0 + 1)
        if r.startswith("OK"):
            return int(r.split()[1])
        raise RuntimeError("write: " + r)

    def read(self, endpoint, size, timeout=1000):
        """Bulk/interrupt IN read. Returns b'' on timeout."""
        r = self._cmd("R %02x %d %d" % (endpoint, size, timeout),
                      timeout / 1000.0 + 2)
        if r.startswith("D "):
            return bytes.fromhex(r[2:])
        if r in ("D", "T"):
            return b""
        raise RuntimeError("read: " + r)

    def close(self):
        try:
            self._cmd("X")
        except Exception:
            pass
        self.s.close()
        _bridge("usbraw\tclose")


class Serial:
    """Serial adapter - pyserial-like API over the app's serial relay."""

    def __init__(self, vid_pid, baud=115200):
        _need_ok(_bridge("usbser\topen\t%s\t%d" % (vid_pid, baud)),
                 "serial open " + vid_pid)
        self.vid_pid = vid_pid
        self.baud = baud
        self.s = socket.create_connection((_HOST, _SERIAL), timeout=5)
        self._buf = b""

    def write(self, data):
        self.s.sendall(bytes(data))
        return len(data)

    def read(self, size=1, timeout=5.0):
        self.s.settimeout(timeout)
        while len(self._buf) < size:
            d = self.s.recv(65536)
            if not d:
                break
            self._buf += d
        out, self._buf = self._buf[:size], self._buf[size:]
        return out

    def readline(self, timeout=5.0):
        self.s.settimeout(timeout)
        while b"\n" not in self._buf:
            d = self.s.recv(65536)
            if not d:
                break
            self._buf += d
        i = self._buf.find(b"\n")
        if i < 0:
            out, self._buf = self._buf, b""
        else:
            out, self._buf = self._buf[:i + 1], self._buf[i + 1:]
        return out

    def read_all(self, wait=0.3):
        """Drain everything that arrives within `wait` seconds."""
        self.s.settimeout(wait)
        while True:
            try:
                d = self.s.recv(65536)
            except socket.timeout:
                break
            if not d:
                break
            self._buf += d
        out, self._buf = self._buf, b""
        return out

    def dtr(self, on):
        _need_ok(_bridge("usbser\tdtr\t" + ("on" if on else "off")), "dtr")

    def rts(self, on):
        _need_ok(_bridge("usbser\trts\t" + ("on" if on else "off")), "rts")

    def close(self):
        self.s.close()
        _bridge("usbser\tclose")


class Flash:
    """FAT32 USB flash drive."""

    def __init__(self, vid_pid):
        _need_ok(_bridge("flash\topen\t" + vid_pid), "flash open " + vid_pid)
        self.vid_pid = vid_pid

    def _t(self, cmd, timeout=15.0):
        return _bridge(cmd, timeout).decode("utf-8", "replace").strip()

    def info(self):
        return self._t("flash\tinfo")

    def ls(self, path="/"):
        return self._t("flash\tls\t" + path)

    def mkdir(self, path):
        return self._t("flash\tmkdir\t" + path)

    def rm(self, path):
        return self._t("flash\trm\t" + path)

    def cat(self, path, timeout=60.0):
        """File content as bytes (small/medium files)."""
        data = _bridge("flash\tcat\t" + path, timeout)
        if data.startswith(b"ERR"):
            raise RuntimeError(data.decode("utf-8", "replace").strip())
        return data

    def get(self, remote, local=None):
        """Streamed download. Returns bytes, or the size if `local` given."""
        s = socket.create_connection((_HOST, _FLASH), timeout=15)
        try:
            s.sendall(("get\t%s\n" % remote).encode("utf-8"))
            chunks = []
            while True:
                d = s.recv(256 * 1024)
                if not d:
                    break
                chunks.append(d)
            data = b"".join(chunks)
            if data.startswith(b"ERR"):
                raise RuntimeError(data.decode("utf-8", "replace").strip())
            if local:
                with open(local, "wb") as f:
                    f.write(data)
                return len(data)
            return data
        finally:
            s.close()

    def put(self, local, remote):
        """Upload a local file to the stick."""
        with open(local, "rb") as f:
            data = f.read()
        s = socket.create_connection((_HOST, _FLASH), timeout=30)
        try:
            s.sendall(("put\t%s\t%d\n" % (remote, len(data))).encode("utf-8") + data)
            r = b""
            while not r.endswith(b"\n"):
                d = s.recv(65536)
                if not d:
                    break
                r += d
            txt = r.decode("utf-8", "replace").strip()
            if not txt.startswith("OK"):
                raise RuntimeError(txt)
            return txt
        finally:
            s.close()

    def format(self, label="FLASH"):
        """Quick-format as FAT32. ERASES EVERYTHING ON THE STICK."""
        return self._t("flash\tformat\tCONFIRM\t" + label, timeout=120.0)

    def close(self):
        return self._t("flash\teject")
