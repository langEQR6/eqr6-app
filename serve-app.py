"""
EQR6 app update server.

Serves the APK and the version manifest over HTTP so the phone can fetch
updates without needing SMB. Binds to all interfaces; access control is
enforced by Windows Firewall, which only permits the Tailscale range
(100.64.0.0/10). Nothing is exposed to the public internet.

Run:
    python serve-app.py            # serves on 0.0.0.0:8080

The directory served is D:\\Server\\Share\\app.
"""

import http.server
import os
import socketserver
import sys

PORT = 8080
ROOT = r"D:\Server\Share\app"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        # the app must never cache the manifest, or update checks go stale
        if self.path.endswith("version.json"):
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        else:
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):
        # keep the console quiet but record the request
        try:
            sys.stdout.write("%s - %s\n" % (self.address_string(), fmt % args))
            sys.stdout.flush()
        except Exception:
            pass


class ReusableTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    if not os.path.isdir(ROOT):
        print("ERROR: root directory does not exist: %s" % ROOT)
        return 1

    with ReusableTCPServer(("0.0.0.0", PORT), Handler) as httpd:
        print("EQR6 app update server")
        print("  serving : %s" % ROOT)
        print("  address : http://0.0.0.0:%d/" % PORT)
        print("  firewall: only the Tailscale range may connect")
        print("")
        print("  manifest: http://100.77.117.76:%d/version.json" % PORT)
        print("  apk     : http://100.77.117.76:%d/eqr6-app-release.apk" % PORT)
        print("")
        print("Ctrl+C to stop.")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
