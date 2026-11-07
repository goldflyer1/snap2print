#!/usr/bin/env python3
"""
Snap2Print bridge — runs on your PC (Windows, macOS or Linux), next to Bambu Studio.

The phone app POSTs the finished STL here. Bambu printers only print *sliced* files,
so this program hands the STL on in one of three ways (set "mode" in config.json):

  "save"  – just save the STL in the inbox folder.
  "open"  – save it and open it in Bambu Studio; you click "Slice plate" and "Print". (default, most reliable)
  "auto"  – slice it with the Bambu Studio command line and start the print over your LAN.
            Needs the printer in LAN Only + Developer Mode, and exported Bambu Studio profiles.

Only the Python standard library is used: no pip install needed.
Start it with:  python snap2print_bridge.py      (it creates config.json on first run)
"""

import hashlib
import json
import os
import re
import socket
import ssl
import struct
import subprocess
import sys
import time
import ftplib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

HERE = Path(__file__).resolve().parent
CONFIG_PATH = HERE / "config.json"
MAX_UPLOAD = 300 * 1024 * 1024  # 300 MB

DEFAULT_CONFIG = {
    "port": 8765,
    "token": "",
    "inbox": "inbox",
    "mode": "open",
    "bambu_studio": {
        "win32": "C:/Program Files/Bambu Studio/bambu-studio.exe",
        "darwin": "/Applications/BambuStudio.app",
        "linux": "bambu-studio",
    },
    "auto": {
        "machine_profile": "profiles/machine.json",
        "process_profile": "profiles/process.json",
        "filament_profile": "profiles/filament.json",
        "slice_args": [
            "--orient", "1", "--arrange", "1",
            "--load-settings", "{machine};{process}",
            "--load-filaments", "{filament}",
            "--slice", "0",
            "--export-3mf", "{out}",
            "{stl}",
        ],
        "printer_ip": "192.168.1.60",
        "access_code": "12345678",
        "serial": "01P00A000000000",
        "use_ams": False,
        "ams_slot": 0,
        "bed_leveling": True,
        "timelapse": False,
        "print_url_style": "file:///sdcard/{name}",
    },
}


# ---------------------------------------------------------------- config

def load_config():
    if not CONFIG_PATH.exists():
        CONFIG_PATH.write_text(json.dumps(DEFAULT_CONFIG, indent=2))
        print(f"Created {CONFIG_PATH.name} with default settings. Edit it if needed, then restart.")
    cfg = json.loads(CONFIG_PATH.read_text())
    merged = json.loads(json.dumps(DEFAULT_CONFIG))
    for k, v in cfg.items():
        if isinstance(v, dict) and isinstance(merged.get(k), dict):
            merged[k].update(v)
        else:
            merged[k] = v
    return merged


def resolve(path):
    p = Path(path)
    return p if p.is_absolute() else HERE / p


def studio_path(cfg):
    s = cfg["bambu_studio"]
    if isinstance(s, dict):
        key = "win32" if sys.platform.startswith("win") else ("darwin" if sys.platform == "darwin" else "linux")
        return s.get(key, "")
    return s


def studio_executable(cfg):
    """The binary to run for command-line slicing (inside the .app bundle on macOS)."""
    p = studio_path(cfg)
    if sys.platform == "darwin" and p.endswith(".app"):
        return str(Path(p) / "Contents" / "MacOS" / "BambuStudio")
    return p


# ---------------------------------------------------------------- modes

def open_in_studio(cfg, stl: Path):
    p = studio_path(cfg)
    if sys.platform == "darwin":
        cmd = ["open", "-a", p, str(stl)]
    else:
        cmd = [p, str(stl)]
    subprocess.Popen(cmd)
    return "Opened in Bambu Studio on the PC — click Slice plate, then Print."


def slice_with_studio(cfg, stl: Path) -> Path:
    a = cfg["auto"]
    out = stl.with_suffix(".gcode.3mf")
    values = {
        "machine": str(resolve(a["machine_profile"])),
        "process": str(resolve(a["process_profile"])),
        "filament": str(resolve(a["filament_profile"])),
        "out": str(out),
        "stl": str(stl),
    }
    for key in ("machine", "process", "filament"):
        if not Path(values[key]).exists():
            raise RuntimeError(f"Profile not found: {values[key]} (see README: exporting profiles)")
    args = [arg.format(**values) for arg in a["slice_args"]]
    cmd = [studio_executable(cfg)] + args
    print("Slicing:", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if not out.exists():
        tail = (r.stdout + r.stderr)[-800:]
        raise RuntimeError(f"Bambu Studio did not produce a sliced file (exit {r.returncode}). {tail}")
    return out


class ImplicitFTPS(ftplib.FTP_TLS):
    """Bambu printers use implicit FTPS on port 990 and expect TLS session reuse on the data channel."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._sock = None

    @property
    def sock(self):
        return self._sock

    @sock.setter
    def sock(self, value):
        if value is not None and not isinstance(value, ssl.SSLSocket):
            value = self.context.wrap_socket(value)
        self._sock = value

    def ntransfercmd(self, cmd, rest=None):
        conn, size = ftplib.FTP.ntransfercmd(self, cmd, rest)
        if self._prot_p:
            conn = self.context.wrap_socket(conn, server_hostname=self.host, session=self.sock.session)
        return conn, size


def insecure_context():
    # The printer uses a self-signed certificate; this only ever talks to the printer on your own LAN.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


def ftps_upload(ip, access_code, local: Path):
    ftp = ImplicitFTPS(context=insecure_context(), timeout=30)
    ftp.connect(ip, 990)
    ftp.login("bblp", access_code)
    ftp.prot_p()
    with open(local, "rb") as f:
        ftp.storbinary(f"STOR {local.name}", f)
    ftp.quit()


def _mqtt_str(s: str) -> bytes:
    b = s.encode()
    return struct.pack("!H", len(b)) + b


def _mqtt_len(n: int) -> bytes:
    out = bytearray()
    while True:
        byte = n % 128
        n //= 128
        if n:
            byte |= 0x80
        out.append(byte)
        if not n:
            return bytes(out)


def mqtt_connect_packet(client_id, user, password) -> bytes:
    var = _mqtt_str("MQTT") + bytes([4, 0b11000010]) + struct.pack("!H", 60)
    payload = _mqtt_str(client_id) + _mqtt_str(user) + _mqtt_str(password)
    body = var + payload
    return bytes([0x10]) + _mqtt_len(len(body)) + body


def mqtt_publish_packet(topic, message: bytes) -> bytes:
    body = _mqtt_str(topic) + message
    return bytes([0x30]) + _mqtt_len(len(body)) + body


def mqtt_publish(ip, access_code, topic, payload: dict):
    """Minimal MQTT 3.1.1 over TLS: CONNECT, wait for CONNACK, PUBLISH (QoS 0), DISCONNECT."""
    raw = socket.create_connection((ip, 8883), timeout=15)
    s = insecure_context().wrap_socket(raw, server_hostname=ip)
    try:
        s.sendall(mqtt_connect_packet(f"snap2print-{os.getpid()}", "bblp", access_code))
        ack = s.recv(4)
        if len(ack) < 4 or ack[0] != 0x20:
            raise RuntimeError("Printer did not accept the MQTT connection")
        if ack[3] != 0:
            raise RuntimeError(f"Printer refused MQTT login (code {ack[3]}). Check the access code.")
        s.sendall(mqtt_publish_packet(topic, json.dumps(payload).encode()))
        time.sleep(1.0)
        s.sendall(b"\xe0\x00")
    finally:
        s.close()


def print_payload(a, name: str, md5: str) -> dict:
    task = re.sub(r"\.gcode\.3mf$", "", name)
    return {
        "print": {
            "sequence_id": str(int(time.time())),
            "command": "project_file",
            "param": "Metadata/plate_1.gcode",
            "project_id": "0",
            "profile_id": "0",
            "task_id": "0",
            "subtask_id": "0",
            "subtask_name": task,
            "url": a["print_url_style"].format(name=name),
            "md5": md5,
            "timelapse": bool(a["timelapse"]),
            "bed_type": "auto",
            "bed_levelling": bool(a["bed_leveling"]),
            "flow_cali": False,
            "vibration_cali": True,
            "layer_inspect": False,
            "use_ams": bool(a["use_ams"]),
            "ams_mapping": [int(a["ams_slot"])] if a["use_ams"] else [],
        }
    }


def auto_print(cfg, stl: Path):
    a = cfg["auto"]
    sliced = slice_with_studio(cfg, stl)
    print("Uploading", sliced.name, "to", a["printer_ip"])
    ftps_upload(a["printer_ip"], a["access_code"], sliced)
    md5 = hashlib.md5(sliced.read_bytes()).hexdigest().upper()
    mqtt_publish(a["printer_ip"], a["access_code"], f"device/{a['serial']}/request",
                 print_payload(a, sliced.name, md5))
    return f"Sliced and sent to the printer ({sliced.name}). Check the printer screen."


# ---------------------------------------------------------------- HTTP

def safe_name(name: str) -> str:
    stem = re.sub(r"[^A-Za-z0-9_.-]", "_", Path(name or "model.stl").name)
    if not stem.lower().endswith(".stl"):
        stem += ".stl"
    return stem


class Handler(BaseHTTPRequestHandler):
    cfg = None

    def _reply(self, code, message, **extra):
        body = json.dumps({"ok": code < 400, "message": message, **extra}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self):
        token = self.cfg.get("token") or ""
        return not token or self.headers.get("X-Snap2Print-Token") == token

    def do_GET(self):
        if urlparse(self.path).path == "/ping":
            return self._reply(200, f"Snap2Print bridge is running (mode: {self.cfg['mode']})")
        self._reply(404, "Not found")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/print":
            return self._reply(404, "Not found")
        if not self._authorised():
            return self._reply(401, "Wrong token")
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 84 or length > MAX_UPLOAD:
            return self._reply(400, "Missing or oversized STL")
        data = self.rfile.read(length)

        inbox = resolve(self.cfg["inbox"])
        inbox.mkdir(parents=True, exist_ok=True)
        name = safe_name(parse_qs(url.query).get("name", ["model.stl"])[0])
        target = inbox / name
        target.write_bytes(data)
        print(f"Received {name} ({length:,} bytes) from {self.client_address[0]}")

        mode = self.cfg["mode"]
        try:
            if mode == "save":
                msg = f"Saved on the PC as {target}"
            elif mode == "open":
                msg = open_in_studio(self.cfg, target)
            elif mode == "auto":
                msg = auto_print(self.cfg, target)
            else:
                return self._reply(500, f"Unknown mode '{mode}' in config.json")
        except Exception as e:  # report problems back to the phone
            print("Error:", e)
            return self._reply(500, f"Saved {name}, but: {e}")
        self._reply(200, msg, file=str(target))

    def log_message(self, fmt, *args):
        pass


def local_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))  # no packet is sent; this just picks the LAN interface
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def main():
    cfg = load_config()
    Handler.cfg = cfg
    port = int(cfg["port"])
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Snap2Print bridge running in '{cfg['mode']}' mode.")
    print(f"In the phone app, set the bridge address to:  http://{local_ip()}:{port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
