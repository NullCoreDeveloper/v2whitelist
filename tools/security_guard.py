#!/usr/bin/env python3
"""
Автоматическая проверка регрессий безопасности для ПроБел (v2whitelist).
Использует только стандартную библиотеку Python 3.
"""
from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def fail(errors: list[str], msg: str) -> None:
    errors.append(msg)


def check_android_manifest(errors: list[str]) -> None:
    manifest = ROOT / "V2rayNG/app/src/main/AndroidManifest.xml"
    if not manifest.is_file():
        fail(errors, f"Missing {manifest}")
        return

    tree = ET.parse(manifest)
    root = tree.getroot()
    app = root.find("application")
    if app is None:
        fail(errors, "AndroidManifest.xml: <application> element not found")
        return

    if app.attrib.get(f"{ANDROID_NS}allowBackup") != "false":
        fail(errors, 'AndroidManifest.xml: android:allowBackup must be "false"')

    if app.attrib.get(f"{ANDROID_NS}usesCleartextTraffic", "").lower() == "true":
        fail(errors, 'AndroidManifest.xml: android:usesCleartextTraffic="true" is forbidden')

    for rec in app.findall("receiver"):
        name = rec.attrib.get(f"{ANDROID_NS}name", "")
        if name.endswith("BootReceiver") and rec.attrib.get(f"{ANDROID_NS}exported") == "true":
            fail(errors, f'AndroidManifest.xml: {name} must not have android:exported="true"')


def check_network_security_config(errors: list[str]) -> None:
    nsc = ROOT / "V2rayNG/app/src/main/res/xml/network_security_config.xml"
    if not nsc.is_file():
        fail(errors, f"Missing {nsc}")
        return

    content = nsc.read_text(encoding="utf-8")
    if re.search(r'<certificates\s+[^>]*src=["\']user["\']', content):
        fail(errors, 'network_security_config.xml: <certificates src="user" .../> is forbidden')


def check_no_global_authenticator(errors: list[str]) -> None:
    roots = [
        ROOT / "V2rayNG/app/src/main/java",
        ROOT / "V2rayNG/desktopApp/src/desktopMain/kotlin",
    ]
    for base in roots:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.suffix in (".kt", ".java") and path.is_file():
                text = path.read_text(encoding="utf-8", errors="ignore")
                if "Authenticator.setDefault(" in text:
                    rel = path.relative_to(ROOT)
                    fail(errors, f"{rel}: global Authenticator.setDefault( is forbidden")


def check_xray_desktop_runner(errors: list[str]) -> None:
    runner = ROOT / "V2rayNG/desktopApp/src/desktopMain/kotlin/com/kiktor/v2whitelist/desktop/engine/XrayDesktopRunner.kt"
    if not runner.is_file():
        fail(errors, f"Missing {runner}")
        return
    text = runner.read_text(encoding="utf-8")
    if 'File.createTempFile("v2w_xray_config_' in text:
        fail(errors, 'XrayDesktopRunner.kt: File.createTempFile("v2w_xray_config_ is forbidden; pass config via stdin:')


def check_tproxy_service(errors: list[str]) -> None:
    tproxy = ROOT / "V2rayNG/app/src/main/java/com/kiktor/v2whitelist/service/TProxyService.kt"
    if not tproxy.is_file():
        fail(errors, f"Missing {tproxy}")
        return
    text = tproxy.read_text(encoding="utf-8")
    if 'writeText(' in text and 'hev-socks5-tunnel.yaml' in text:
        fail(errors, "TProxyService.kt: writing hev-socks5-tunnel.yaml to disk is forbidden")
    if 'TProxyStartServiceFromStr(' not in text:
        fail(errors, "TProxyService.kt: TProxyStartServiceFromStr( must be used")


def check_compile_hevtun(errors: list[str]) -> None:
    script = ROOT / "compile-hevtun.sh"
    if not script.is_file():
        fail(errors, f"Missing {script}")
        return
    text = script.read_text(encoding="utf-8")
    if "PKGNAME=com/v2ray/ang/service" in text:
        fail(errors, "compile-hevtun.sh: legacy PKGNAME=com/v2ray/ang/service is forbidden")
    if "PKGNAME=com/kiktor/v2whitelist/service" not in text:
        fail(errors, "compile-hevtun.sh: PKGNAME=com/kiktor/v2whitelist/service is required")


def check_workflows(errors: list[str]) -> None:
    wf_dir = ROOT / ".github/workflows"
    if not wf_dir.is_dir():
        fail(errors, f"Missing {wf_dir}")
        return
    for wf in sorted(wf_dir.glob("*.yml")):
        text = wf.read_text(encoding="utf-8")
        rel = wf.relative_to(ROOT)
        if not re.search(r'(?m)^permissions:\s*$', text):
            fail(errors, f"{rel}: top-level permissions: block is required")
        for idx, line in enumerate(text.splitlines(), 1):
            if "curl " in line and re.search(r'\bcurl\s+[^#\n]*-sSL\b', line):
                fail(errors, f"{rel}:{idx}: curl -sSL without -f/--fail is forbidden (use curl -fsSL)")


def main() -> int:
    errors: list[str] = []
    check_android_manifest(errors)
    check_network_security_config(errors)
    check_no_global_authenticator(errors)
    check_xray_desktop_runner(errors)
    check_tproxy_service(errors)
    check_compile_hevtun(errors)
    check_workflows(errors)

    if errors:
        print("SECURITY GUARD FAILED:")
        for err in errors:
            print(f"  - {err}")
        return 1

    print("SECURITY GUARD OK: all 7 policy checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
