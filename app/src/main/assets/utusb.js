/**
 * utusb - USB access for the Ubuntu Terminal app. NO root needed.
 *
 * Node.js library (built-in `net` module only) talking to the app's USB
 * bridges over local TCP sockets:
 *
 *   utusb.listusb()                        all USB devices
 *   utusb.RawUsb.open("16c0:0486")         raw USB (control/bulk/interrupt)
 *   utusb.Serial.open("1a86:7523", 115200) serial adapters (CH340/CP210x/..)
 *   utusb.Flash.open("0951:1666")          FAT32 USB flash drives
 *
 * Quick start:
 *
 *   const utusb = require("utusb");
 *
 *   // raw USB
 *   const dev = await utusb.RawUsb.open("16c0:0486");
 *   console.log((await dev.ctrlTransfer(0x80, 6, 0x0100, 0, 18)).toString("hex"));
 *   await dev.write(0x01, Buffer.from("hello"));
 *   console.log((await dev.read(0x81, 64)).toString("hex"));
 *   await dev.close();
 *
 *   // serial
 *   const ser = await utusb.Serial.open("1a86:7523", 115200);
 *   await ser.write("AT\r\n");
 *   console.log((await ser.readline()).toString());
 *   await ser.close();
 *
 *   // flash drive
 *   const fl = await utusb.Flash.open("0951:1666");
 *   console.log(await fl.ls("/"));
 *   await fl.get("/notes.txt", "notes.txt");
 *   await fl.put("backup.tar.gz", "/backup.tar.gz");
 *   await fl.close();
 */

"use strict";

const net = require("net");
const HOST = "127.0.0.1";
const CTRL = 8037;    // dapi request/response bridge
const RAW = 8040;     // raw USB line protocol
const SERIAL = 8039;  // serial relay
const FLASH = 8041;   // flash transfer stream

function bridge(cmd, timeoutMs) {
  const timeout = timeoutMs || 15000;
  return new Promise((resolve, reject) => {
    const s = net.connect(CTRL, HOST);
    const chunks = [];
    const t = setTimeout(() => { s.destroy(); reject(new Error("bridge timeout")); }, timeout);
    s.on("connect", () => s.write(cmd + "\n"));
    s.on("data", (d) => chunks.push(d));
    s.on("close", () => { clearTimeout(t); resolve(Buffer.concat(chunks)); });
    s.on("error", (e) => { clearTimeout(t); reject(e); });
  });
}

async function needOk(resp, what) {
  const txt = resp.toString("utf8");
  if (txt.startsWith("OK") || txt.startsWith("closed") || txt.startsWith("no ")) return txt;
  if (txt.indexOf("permission") >= 0)
    throw new Error("USB permission needed - allow the dialog on the phone, then retry");
  throw new Error(what + " failed: " + txt.trim());
}

async function listUsb() { return (await bridge("usb")).toString("utf8"); }
async function listRaw() { return (await bridge("usbraw\tlist")).toString("utf8"); }
async function listFlash() { return (await bridge("flash\tlist")).toString("utf8"); }

/** Line-protocol client for the raw USB bridge (port 8040). */
class RawUsb {
  constructor(sock) { this.s = sock; this.buf = ""; }

  static async open(vidPid) {
    await needOk(await bridge("usbraw\topen\t" + vidPid), "usbraw open " + vidPid);
    return new Promise((resolve, reject) => {
      const s = net.connect(RAW, HOST);
      s.on("connect", () => resolve(new RawUsb(s)));
      s.on("error", reject);
    });
  }

  _cmd(line, timeoutMs) {
    const timeout = timeoutMs || 5000;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("raw bridge timeout")), timeout);
      const onData = (d) => {
        this.buf += d.toString("ascii");
        let i = this.buf.indexOf("\n");
        if (i >= 0) {
          const line = this.buf.slice(0, i);
          this.buf = this.buf.slice(i + 1);
          this.s.removeListener("data", onData);
          clearTimeout(t);
          resolve(line.trim());
        }
      };
      this.s.on("data", onData);
      this.s.write(line + "\n");
    });
  }

  async endpoints() {
    const r = await this._cmd("I");
    return r.split("\n");
  }

  /** pyusb-style control transfer. IN: pass a length; OUT: pass a Buffer. */
  async ctrlTransfer(bmRequestType, bRequest, wValue, wIndex, dataOrLength, timeoutMs) {
    const timeout = (timeoutMs || 3000) + 1000;
    if (bmRequestType & 0x80) {
      const len = dataOrLength | 0;
      const r = await this._cmd(
        "C " + hex(bmRequestType, 2) + " " + hex(bRequest, 2) + " " +
        hex(wValue, 4) + " " + hex(wIndex, 4) + " " + len, timeout);
      if (r.startsWith("D ")) return Buffer.from(r.slice(2), "hex");
      if (r === "D") return Buffer.alloc(0);
      throw new Error("ctrlTransfer: " + r);
    }
    const data = Buffer.isBuffer(dataOrLength) ? dataOrLength : Buffer.alloc(0);
    const r = await this._cmd(
      "C " + hex(bmRequestType, 2) + " " + hex(bRequest, 2) + " " +
      hex(wValue, 4) + " " + hex(wIndex, 4) + " " + data.length + " " + data.toString("hex"),
      timeout);
    if (r.startsWith("OK")) return parseInt(r.split(" ")[1], 10);
    throw new Error("ctrlTransfer: " + r);
  }

  /** Bulk/interrupt OUT write to an endpoint address (e.g. 0x01). */
  async write(endpoint, data, timeoutMs) {
    const r = await this._cmd("W " + hex(endpoint, 2) + " " + data.toString("hex"),
      (timeoutMs || 3000) + 1000);
    if (r.startsWith("OK")) return parseInt(r.split(" ")[1], 10);
    throw new Error("write: " + r);
  }

  /** Bulk/interrupt IN read. Resolves to an empty Buffer on timeout. */
  async read(endpoint, size, timeoutMs) {
    const timeout = timeoutMs || 1000;
    const r = await this._cmd("R " + hex(endpoint, 2) + " " + size + " " + timeout,
      timeout + 2000);
    if (r.startsWith("D ")) return Buffer.from(r.slice(2), "hex");
    if (r === "D" || r === "T") return Buffer.alloc(0);
    throw new Error("read: " + r);
  }

  async close() {
    try { await this._cmd("X"); } catch (e) { /* ignore */ }
    this.s.destroy();
    await bridge("usbraw\tclose").catch(() => {});
  }
}

/** Serial adapter with a pyserial-like API over the relay (port 8039). */
class Serial {
  constructor(sock) { this.s = sock; this.buf = Buffer.alloc(0); }

  static async open(vidPid, baud) {
    await needOk(await bridge("usbser\topen\t" + vidPid + "\t" + (baud || 115200)),
      "serial open " + vidPid);
    return new Promise((resolve, reject) => {
      const s = net.connect(SERIAL, HOST);
      s.on("connect", () => resolve(new Serial(s)));
      s.on("error", reject);
    });
  }

  write(data) {
    const b = Buffer.isBuffer(data) ? data : Buffer.from(data);
    this.s.write(b);
    return Promise.resolve(b.length);
  }

  _recvUntil(pred, timeoutMs) {
    const timeout = timeoutMs || 5000;
    return new Promise((resolve) => {
      const onData = (d) => { this.buf = Buffer.concat([this.buf, d]); check(); };
      const check = () => {
        if (pred()) {
          clearTimeout(t);
          this.s.removeListener("data", onData);
          resolve();
        }
      };
      const t = setTimeout(() => {
        this.s.removeListener("data", onData);
        resolve();
      }, timeout);
      this.s.on("data", onData);
      check();
    });
  }

  async read(size, timeoutMs) {
    await this._recvUntil(() => this.buf.length >= size, timeoutMs);
    const out = this.buf.slice(0, size);
    this.buf = this.buf.slice(size);
    return out;
  }

  async readline(timeoutMs) {
    await this._recvUntil(() => this.buf.indexOf(10) >= 0, timeoutMs);
    const i = this.buf.indexOf(10);
    if (i < 0) { const out = this.buf; this.buf = Buffer.alloc(0); return out; }
    const out = this.buf.slice(0, i + 1);
    this.buf = this.buf.slice(i + 1);
    return out;
  }

  async drain(waitMs) {
    await new Promise((r) => setTimeout(r, waitMs || 300));
    const out = this.buf;
    this.buf = Buffer.alloc(0);
    return out;
  }

  async dtr(on) { await needOk(await bridge("usbser\tdtr\t" + (on ? "on" : "off")), "dtr"); }
  async rts(on) { await needOk(await bridge("usbser\trts\t" + (on ? "on" : "off")), "rts"); }

  async close() {
    this.s.destroy();
    await bridge("usbser\tclose").catch(() => {});
  }
}

/** FAT32 USB flash drive. */
class Flash {
  constructor(vidPid) { this.vidPid = vidPid; }

  static async open(vidPid) {
    await needOk(await bridge("flash\topen\t" + vidPid), "flash open " + vidPid);
    return new Flash(vidPid);
  }

  async _t(cmd, timeoutMs) {
    return (await bridge(cmd, timeoutMs || 20000)).toString("utf8").trim();
  }

  info() { return this._t("flash\tinfo"); }
  ls(path) { return this._t("flash\tls\t" + (path || "/")); }
  mkdir(path) { return this._t("flash\tmkdir\t" + path); }
  rm(path) { return this._t("flash\trm\t" + path); }
  format(label) { return this._t("flash\tformat\tCONFIRM\t" + (label || "FLASH"), 180000); }
  close() { return this._t("flash\teject"); }

  /** File content as a Buffer. */
  async cat(path) {
    const data = await bridge("flash\tcat\t" + path, 60000);
    if (data.slice(0, 3).toString("utf8") === "ERR")
      throw new Error(data.toString("utf8").trim());
    return data;
  }

  /** Streamed download. Returns a Buffer, or writes to `local` if given. */
  async get(remote, local) {
    return new Promise((resolve, reject) => {
      const s = net.connect(FLASH, HOST);
      const chunks = [];
      s.on("connect", () => s.write("get\t" + remote + "\n"));
      s.on("data", (d) => chunks.push(d));
      s.on("close", async () => {
        const data = Buffer.concat(chunks);
        if (data.slice(0, 3).toString("utf8") === "ERR") return reject(new Error(data.toString("utf8").trim()));
        if (local) {
          const fs = require("fs");
          fs.writeFileSync(local, data);
          resolve(data.length);
        } else resolve(data);
      });
      s.on("error", reject);
    });
  }

  /** Upload a local file to the stick. */
  async put(local, remote) {
    const fs = require("fs");
    const data = fs.readFileSync(local);
    return new Promise((resolve, reject) => {
      const s = net.connect(FLASH, HOST);
      let r = "";
      s.on("connect", () => {
        s.write("put\t" + remote + "\t" + data.length + "\n");
        s.write(data);
      });
      s.on("data", (d) => { r += d.toString("utf8"); if (r.indexOf("\n") >= 0) s.end(); });
      s.on("close", () => {
        const txt = r.trim();
        if (txt.startsWith("OK")) resolve(txt);
        else reject(new Error(txt || "put failed"));
      });
      s.on("error", reject);
    });
  }
}

function hex(n, width) {
  let s = (n & 0xFFFFFFFF).toString(16);
  while (s.length < width) s = "0" + s;
  return s;
}

module.exports = { RawUsb, Serial, Flash, listUsb, listRaw, listFlash, bridge };
