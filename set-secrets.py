"""
Set GitHub Actions secrets for the EQR6 app repository.

Uses libsodium sealed boxes as required by the GitHub API:
  1. fetch the repository public key
  2. encrypt each value with it
  3. PUT the encrypted value

Run:
    set GH_TOKEN=...    (or pass as env var)
    python set-secrets.py
"""

import base64
import json
import os
import sys
import urllib.request
import urllib.error

from nacl import encoding, public

OWNER = "langEQR6"
REPO = "eqr6-app"
API = "https://api.github.com"


def token() -> str:
    t = os.environ.get("GH_TOKEN", "").strip()
    if not t:
        print("ERROR: GH_TOKEN environment variable is not set")
        sys.exit(1)
    return t


def api(method: str, path: str, body=None, tok: str = ""):
    url = API + path
    data = None
    headers = {
        "Authorization": "token %s" % tok,
        "Accept": "application/vnd.github+json",
        "User-Agent": "EQR6-Server",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        return e.code, raw


def encrypt(public_key_b64: str, value: str) -> str:
    pk = public.PublicKey(public_key_b64.encode("utf-8"), encoding.Base64Encoder())
    sealed = public.SealedBox(pk).encrypt(value.encode("utf-8"))
    return base64.b64encode(sealed).decode("utf-8")


def main() -> int:
    tok = token()

    # ---- confirm the repo is reachable ----
    code, repo = api("GET", "/repos/%s/%s" % (OWNER, REPO), tok=tok)
    if code != 200:
        print("ERROR: cannot read repo (%s): %s" % (code, repo))
        return 1
    print("repo: %s" % repo["full_name"])

    # ---- repository public key ----
    code, key = api("GET", "/repos/%s/%s/actions/secrets/public-key" % (OWNER, REPO), tok=tok)
    if code != 200:
        print("ERROR: cannot fetch public key (%s): %s" % (code, key))
        return 1
    key_id = key["key_id"]
    print("public key id: %s" % key_id)

    # ---- the secrets to install ----
    keystore_path = os.environ.get("EQR6_KEYSTORE", r"D:\Server\stock\eqr6-app\keystore\eqr6-release.jks")
    if not os.path.isfile(keystore_path):
        print("ERROR: keystore not found: %s" % keystore_path)
        return 1

    with open(keystore_path, "rb") as f:
        ks_b64 = base64.b64encode(f.read()).decode("ascii")

    secrets = {
        "KEYSTORE_BASE64": ks_b64,
        "KEYSTORE_PASSWORD": "eqr6app2026",
        "KEY_ALIAS": "eqr6",
        "KEY_PASSWORD": "eqr6app2026",
    }

    print("setting %d secrets ..." % len(secrets))
    failures = 0
    for name, value in secrets.items():
        enc = encrypt(key["key"], value)
        code, res = api(
            "PUT",
            "/repos/%s/%s/actions/secrets/%s" % (OWNER, REPO, name),
            body={"encrypted_value": enc, "key_id": key_id},
            tok=tok,
        )
        if code in (201, 204):
            print("  OK   %s (%d chars)" % (name, len(value)))
        else:
            print("  FAIL %s -> HTTP %s %s" % (name, code, res))
            failures += 1

    # ---- verify by listing secret names ----
    code, listing = api("GET", "/repos/%s/%s/actions/secrets" % (OWNER, REPO), tok=tok)
    if code == 200:
        names = sorted(s["name"] for s in listing.get("secrets", []))
        print("secrets now present: %s" % ", ".join(names))
    else:
        print("could not list secrets: %s" % code)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
