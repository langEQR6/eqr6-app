"""
EQR6 toolbox API.

Serves two things on one port:
  * the Android app's OTA files  (/version.json, /eqr6-app-release.apk, /history/...)
  * a small JSON API the app uses as a server remote control

Access control is enforced by Windows Firewall: only the local subnet and the
Tailscale range (100.64.0.0/10) may connect. Nothing is exposed publicly.

Authentication: every /api/* request must carry the header
    X-API-Key: <key>
The key lives in api-key.txt next to this file. Without it the API returns 401.

Security decisions worth knowing:
  * There is deliberately NO endpoint to stop sshd or the Tailscale service.
    Those two are the only way back into this machine; disabling them remotely
    would lock the operator out with no recovery path.  sshd status can be read
    and the service can be RESTARTED, but never stopped.
  * Service control is limited to a fixed allow-list of scheduled tasks and a
    fixed set of actions. Arbitrary commands are never accepted.
"""

import json
import os
import re
import subprocess
import sys
import socketserver
import http.server
import socket
import time
from datetime import datetime
from urllib.parse import urlparse, parse_qs

# ----------------------------------------------------------------------------
# configuration
# ----------------------------------------------------------------------------

PORT = 8080
APP_ROOT = r"D:\Server\Share\app"          # OTA files
LOG_DIR = r"D:\Server\logs"
VERSIONS_DIR = r"D:\Server\Share\app\history"
KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "api-key.txt")

MAX_LOG_LINES = 2000
DEFAULT_LOG_LINES = 200

# Scheduled tasks the app may control.
ALLOWED_TASKS = {
    "DSH-Web-Autostart": "DSH 网页服务",
    "EQR6-AppUpdateServer": "App 更新服务",
    "EQR6-Health-Watchdog": "健康巡逻",
}

# Services the app may inspect. restart=True means the app may restart it.
# sshd and Tailscale are explicitly restart-only, never stoppable.
ALLOWED_SERVICES = {
    "sshd": {"label": "SSH 服务", "restart": True, "stop": False},
    "Tailscale": {"label": "Tailscale", "restart": True, "stop": False},
    "W32Time": {"label": "时间同步", "restart": True, "stop": False},
}


def load_api_key():
    try:
        with open(KEY_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


API_KEY = load_api_key()


# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------

def run(cmd, timeout=25):
    """Run a command, return (returncode, stdout+stderr as text)."""
    try:
        p = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            timeout=timeout,
        )
        out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
        return p.returncode, out.strip()
    except subprocess.TimeoutExpired:
        return -1, "timeout"
    except Exception as e:
        return -1, str(e)


def human_bytes(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return "%.1f %s" % (n, unit)
        n /= 1024.0
    return "%.1f PB" % n


def mtime_str(path):
    try:
        return datetime.fromtimestamp(os.path.getmtime(path)).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return ""


# ----------------------------------------------------------------------------
# API handlers
# ----------------------------------------------------------------------------

def api_status():
    """Overview card: memory, disks, uptime, service snapshot."""
    result = {
        "ok": True,
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "host": socket.gethostname(),
        "memory": {},
        "disks": [],
        "uptimeHours": None,
        "services": [],
        "watchdog": None,
    }

    # ---- memory via CIM ----
    rc, out = run(
        'powershell -NoProfile -Command "'
        "$os=Get-CimInstance Win32_OperatingSystem;"
        "$cs=Get-CimInstance Win32_ComputerSystem;"
        "Write-Output ($cs.TotalPhysicalMemory);"
        "Write-Output ($os.FreePhysicalMemory);"
        "Write-Output ($os.LastBootUpTime.ToString('o'))\""
    )
    if rc == 0:
        lines = [l.strip() for l in out.splitlines() if l.strip()]
        if len(lines) >= 3:
            total = int(lines[0])
            free_kb = int(lines[1])
            total_gb = total / (1024.0 ** 3)
            free_gb = free_kb / (1024.0 ** 2)
            used_pct = 0 if total_gb <= 0 else round((1 - free_gb / total_gb) * 100, 1)
            result["memory"] = {
                "totalGb": round(total_gb, 1),
                "freeGb": round(free_gb, 1),
                "usedPercent": used_pct,
            }
            try:
                boot = datetime.strptime(lines[2][:19], "%Y-%m-%dT%H:%M:%S")
                result["uptimeHours"] = round((datetime.now() - boot).total_seconds() / 3600.0, 1)
            except Exception:
                pass

    # ---- disks via .NET DriveInfo ----
    rc, out = run(
        'powershell -NoProfile -Command "'
        "Get-CimInstance Win32_LogicalDisk -Filter \\\"DriveType=3\\\" | "
        "ForEach-Object { Write-Output ($_.DeviceID + '|' + $_.Size + '|' + $_.FreeSpace) }\""
    )
    if rc == 0:
        for line in out.splitlines():
            line = line.strip()
            if "|" not in line:
                continue
            parts = line.split("|")
            if len(parts) != 3:
                continue
            try:
                total = int(parts[1])
                free = int(parts[2])
                if total <= 0:
                    continue
                result["disks"].append({
                    "drive": parts[0],
                    "totalGb": round(total / (1024.0 ** 3), 1),
                    "freeGb": round(free / (1024.0 ** 3), 1),
                    "usedPercent": round((1 - free / float(total)) * 100, 1),
                })
            except Exception:
                continue

    # ---- service snapshot ----
    for svc in ("sshd", "Tailscale"):
        rc, out = run('powershell -NoProfile -Command "(Get-Service %s).Status"' % svc)
        running = rc == 0 and "Running" in out
        result["services"].append({"name": svc, "running": running})

    rc, out = run(
        'powershell -NoProfile -Command "'
        "if (netstat -ano | Select-String ':3080\\s+.*LISTENING') {'yes'} else {'no'}\""
    )
    result["services"].append({"name": "dsh-web", "running": rc == 0 and "yes" in out})

    rc, out = run(
        'powershell -NoProfile -Command "'
        "if (netstat -ano | Select-String ':8080\\s+.*LISTENING') {'yes'} else {'no'}\""
    )
    result["services"].append({"name": "app-server", "running": rc == 0 and "yes" in out})

    # ---- health watchdog result ----
    try:
        wd = os.path.join(LOG_DIR, "health-watchdog.log")
        if os.path.isfile(wd):
            with open(wd, "r", encoding="utf-8", errors="replace") as f:
                lines = [l for l in f.read().splitlines() if l.strip()]
            if lines:
                last = lines[-1]
                result["watchdog"] = {
                    "last": last,
                    "ok": " OK " in last or "] OK" in last,
                    "lineCount": len(lines),
                }
    except Exception:
        pass

    return result


def _categorise(name):
    n = name.lower()
    if "dsh" in n:
        return "dsh"
    if "watchdog" in n or "health" in n:
        return "watchdog"
    if n.startswith("phase") or "smb" in n or "ssh" in n or "tailscale" in n or "optimise" in n:
        return "setup"
    if "app" in n or "bootstrap" in n:
        return "app"
    return "other"


def api_logs_list():
    files = []
    if os.path.isdir(LOG_DIR):
        for name in os.listdir(LOG_DIR):
            p = os.path.join(LOG_DIR, name)
            if not os.path.isfile(p):
                continue
            try:
                st = os.stat(p)
            except Exception:
                continue
            files.append({
                "name": name,
                "sizeBytes": st.st_size,
                "sizeText": human_bytes(st.st_size),
                "modified": mtime_str(p),
                "mtime": st.st_mtime,
                "category": _categorise(name),
            })
    files.sort(key=lambda x: x["mtime"], reverse=True)
    return {"ok": True, "dir": LOG_DIR, "count": len(files), "files": files}


def api_log_read(name, lines, only_errors=False):
    # reject anything that is not a plain file name in LOG_DIR
    if not re.fullmatch(r"[A-Za-z0-9._\-]+", name or ""):
        return {"ok": False, "error": "invalid file name"}
    path = os.path.join(LOG_DIR, name)
    if not os.path.isfile(path):
        return {"ok": False, "error": "file not found"}

    lines = max(1, min(int(lines or DEFAULT_LOG_LINES), MAX_LOG_LINES))

    with open(path, "r", encoding="utf-8", errors="replace") as f:
        all_lines = f.read().splitlines()

    tail = all_lines[-lines:]
    if only_errors:
        tail = [l for l in tail if re.search(r"ERROR|FAIL|WARN|拒绝|失败", l, re.I)]

    return {
        "ok": True,
        "name": name,
        "totalLines": len(all_lines),
        "returned": len(tail),
        "truncated": len(all_lines) > lines,
        "content": "\n".join(tail),
    }


def api_versions():
    """Current published version plus the retained history."""
    out = {"ok": True, "current": None, "history": []}

    cur = os.path.join(APP_ROOT, "version.json")
    if os.path.isfile(cur):
        try:
            with open(cur, "r", encoding="utf-8") as f:
                out["current"] = json.load(f)
        except Exception:
            pass

    if os.path.isdir(VERSIONS_DIR):
        items = []
        for name in os.listdir(VERSIONS_DIR):
            if not name.lower().endswith(".apk"):
                continue
            p = os.path.join(VERSIONS_DIR, name)
            try:
                st = os.stat(p)
            except Exception:
                continue
            # eqr6-app-1.5-6.apk -> version 1.5, build 6
            m = re.match(r"eqr6-app-(.+)-(\d+)\.apk$", name)
            items.append({
                "name": name,
                "versionName": m.group(1) if m else "?",
                "build": int(m.group(2)) if m else 0,
                "sizeBytes": st.st_size,
                "sizeText": human_bytes(st.st_size),
                "created": mtime_str(p),
            })
        items.sort(key=lambda x: x["build"], reverse=True)
        out["history"] = items

    return out


def api_services():
    tasks = []

    rc, out = run(
        'powershell -NoProfile -Command "'
        "Get-ScheduledTask | Where-Object { $_.TaskName -like 'DSH-*' -or "
        "$_.TaskName -like 'EQR6-*' } | "
        "ForEach-Object { Write-Output ($_.TaskName + '|' + $_.State) }\""
    )
    states = {}
    if rc == 0:
        for line in out.splitlines():
            if "|" in line:
                k, v = line.split("|", 1)
                states[k.strip()] = v.strip()

    for name, label in ALLOWED_TASKS.items():
        tasks.append({
            "name": name,
            "label": label,
            "state": states.get(name, "Unknown"),
            "actions": ["start", "stop"],
        })

    services = []
    for name, meta in ALLOWED_SERVICES.items():
        rc, out = run('powershell -NoProfile -Command "(Get-Service %s).Status"' % name)
        services.append({
            "name": name,
            "label": meta["label"],
            "status": out.strip() if rc == 0 else "Unknown",
            "canRestart": meta["restart"],
            "canStop": meta["stop"],
        })

    return {"ok": True, "tasks": tasks, "services": services}


def api_service_action(kind, name, action):
    """Perform an allow-listed action. Never accepts arbitrary input."""
    if kind == "task":
        if name not in ALLOWED_TASKS:
            return {"ok": False, "error": "task not allowed"}
        if action not in ("start", "stop"):
            return {"ok": False, "error": "action not allowed"}
        if action == "start":
            rc, out = run('powershell -NoProfile -Command "Start-ScheduledTask -TaskName \'%s\'"' % name)
        else:
            rc, out = run('powershell -NoProfile -Command "Stop-ScheduledTask -TaskName \'%s\'"' % name)
        return {"ok": rc == 0, "output": out, "name": name, "action": action}

    if kind == "service":
        if name not in ALLOWED_SERVICES:
            return {"ok": False, "error": "service not allowed"}
        meta = ALLOWED_SERVICES[name]
        if action == "restart":
            rc, out = run(
                'powershell -NoProfile -Command "Restart-Service %s -Force"' % name, timeout=45
            )
            return {"ok": rc == 0, "output": out, "name": name, "action": action}
        if action == "stop":
            # hard guard: these are the way back in, never stoppable remotely
            if not meta["stop"]:
                return {
                    "ok": False,
                    "error": "refused: this service is the remote-access lifeline and "
                             "must not be stopped from the app",
                }
            rc, out = run('powershell -NoProfile -Command "Stop-Service %s -Force"' % name)
            return {"ok": rc == 0, "output": out, "name": name, "action": action}
        return {"ok": False, "error": "action not allowed"}

    return {"ok": False, "error": "unknown kind"}


def api_dsh():
    """The current authenticated DSH URL, plus whether it looks stale.

    DSH regenerates its launch token on every start, so a saved URL is only
    valid for the running process. Staleness is judged by comparing the URL
    file's mtime against the start time of the dsh process.
    """
    result = {"ok": True, "url": None, "stale": None, "dshRunning": False, "note": ""}

    url_file = os.path.join(LOG_DIR, "dsh-web-url.txt")
    url_mtime = None
    if os.path.isfile(url_file):
        with open(url_file, "r", encoding="utf-8", errors="replace") as f:
            result["url"] = f.read().strip()
        url_mtime = os.path.getmtime(url_file)

    rc, out = run(
        'powershell -NoProfile -Command "'
        "$p = Get-CimInstance Win32_Process -Filter \\\"Name='node.exe'\\\" | "
        "Where-Object { $_.CommandLine -match 'dsh\\\\lib\\\\bin\\.js' }; "
        "if ($p) { $p | ForEach-Object { Write-Output ($_.ProcessId.ToString() + '|' + "
        "$_.CreationDate.ToString('o')) } }\""
    )
    dsh_start = None
    if rc == 0 and out.strip():
        first = out.strip().splitlines()[0]
        if "|" in first:
            pid, when = first.split("|", 1)
            result["dshRunning"] = True
            result["pid"] = int(pid)
            try:
                dsh_start = datetime.strptime(when[:19], "%Y-%m-%dT%H:%M:%S").timestamp()
            except Exception:
                pass

    if not result["dshRunning"]:
        result["stale"] = True
        result["note"] = "DSH 进程未运行"
    elif url_mtime is None:
        result["stale"] = True
        result["note"] = "没有找到登录地址文件"
    elif dsh_start is not None:
        # the URL is stale if it was written BEFORE this dsh process started
        result["stale"] = url_mtime < dsh_start
        if result["stale"]:
            result["note"] = "令牌早于当前 DSH 进程，可能已失效"

    return result


# ----------------------------------------------------------------------------
# HTTP layer
# ----------------------------------------------------------------------------

class Handler(http.server.SimpleHTTPRequestHandler):

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=APP_ROOT, **kwargs)

    # ---- helpers ----
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self):
        if not API_KEY:
            return False
        return self.headers.get("X-API-Key", "") == API_KEY

    def end_headers(self):
        if self.path.endswith("version.json"):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        elif not self.path.startswith("/api/"):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):
        try:
            sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
            sys.stdout.flush()
        except Exception:
            pass

    # ---- routing ----
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path.startswith("/api/"):
            if not self._authorised():
                self._json({"ok": False, "error": "unauthorized"}, 401)
                return

            try:
                if path == "/api/status":
                    self._json(api_status())
                elif path == "/api/logs":
                    if "file" in query:
                        self._json(api_log_read(
                            query["file"][0],
                            query.get("lines", [DEFAULT_LOG_LINES])[0],
                            query.get("errors", ["0"])[0] in ("1", "true"),
                        ))
                    else:
                        self._json(api_logs_list())
                elif path == "/api/versions":
                    self._json(api_versions())
                elif path == "/api/services":
                    self._json(api_services())
                elif path == "/api/dsh":
                    self._json(api_dsh())
                elif path == "/api/ping":
                    self._json({"ok": True, "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
                else:
                    self._json({"ok": False, "error": "unknown endpoint"}, 404)
            except Exception as e:
                self._json({"ok": False, "error": str(e)}, 500)
            return

        # everything else: the OTA files
        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if not path.startswith("/api/"):
            self._json({"ok": False, "error": "not found"}, 404)
            return
        if not self._authorised():
            self._json({"ok": False, "error": "unauthorized"}, 401)
            return

        try:
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length).decode("utf-8", "replace") if length else "{}"
            body = json.loads(raw or "{}")
        except Exception:
            body = {}

        m = re.fullmatch(r"/api/services/(task|service)/([A-Za-z0-9_\-]+)/(start|stop|restart)", path)
        if m:
            self._json(api_service_action(m.group(1), m.group(2), m.group(3)))
            return

        self._json({"ok": False, "error": "unknown endpoint"}, 404)


class ReusableTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    if not os.path.isdir(APP_ROOT):
        print("ERROR: app root does not exist: %s" % APP_ROOT)
        return 1

    if not API_KEY:
        print("WARNING: no api-key.txt found - /api/* will reject everything.")

    with ReusableTCPServer(("0.0.0.0", PORT), Handler) as httpd:
        print("EQR6 toolbox server")
        print("  OTA root : %s" % APP_ROOT)
        print("  logs     : %s" % LOG_DIR)
        print("  address  : http://0.0.0.0:%d/" % PORT)
        print("  api key  : %s" % ("configured" if API_KEY else "MISSING"))
        print("")
        print("  manifest : http://100.77.117.76:%d/version.json" % PORT)
        print("  api      : http://100.77.117.76:%d/api/status" % PORT)
        print("")
        print("Ctrl+C to stop.")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
