#!/usr/bin/env python3
"""Apply the Android HTTP-only overlay to a verified upstream checkout."""
from pathlib import Path
import json
import re
import subprocess
import sys

PIN = "1871203ce9665ea6802e8d1328f8b70c7dc9d948"
root = Path(sys.argv[1]).resolve()
assert subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip() == PIN

def replace(path, before, after):
    target = root / path
    text = target.read_text()
    assert before in text, (path, before)
    target.write_text(text.replace(before, after, 1))

(root / "src/backend/Backend.ts").write_text('''import {createHttpApiClient, HttpApi} from "~/backend/HttpApi";
import * as Configs from "~/configs/Config";
import {DownloadRequestItem} from "~/interfaces/DownloadRequestItem";
import {DownloadRequestOptions} from "~/interfaces/DownloadRequestOptions";

let httpApi: HttpApi | null = null;
function getApi() {
    if (httpApi === null) httpApi = createHttpApiClient(Configs.getLatestConfig().port);
    return httpApi;
}
export async function boot() {
    Configs.onChanged.addEventListener(event => { httpApi = createHttpApiClient(event.port); });
}
export function isNativeMessagingSupported() { return false; }
export async function nativeMessagingPing() { return false; }
export async function addDownload(items: DownloadRequestItem[], options: DownloadRequestOptions) {
    return getApi().addDownload({items, options});
}
export async function isAppReachable() { return httpPing(); }
export async function httpPing(port: number | null = null) {
    try { return await (port === null ? getApi() : createHttpApiClient(port)).ping(); }
    catch { return false; }
}
''')
replace("src/backend/HttpApi.ts", "http://localhost:${port}", "http://127.0.0.1:${port}")
replace("src/backend/HttpApi.ts", "const timeout = 500", "const timeout = 2000")
replace("src/manifest/shared.ts", '        "nativeMessaging",\n', "")
replace("src/manifest/manifest.firefox.ts", 'id: "firefox-integration@abdownloadmanager.com",',
        'id: "android-bridge@jolufasoft.com",')
replace("src/manifest/manifest.firefox.ts", '        browser_specific_settings: {\n',
        '        browser_specific_settings: {\n            gecko_android: {},\n')
replace("src/optionsui/OptionUi.tsx", '''            <NativeMessagingSection
                isNativeMessagingAvailable={vm.nativeMessagingAvailable}
            />
            <Divider/>
''', "")
pkg = json.loads((root / "package.json").read_text())
pkg["displayName"] = "AB DM Android Bridge (experimental)"
pkg["version"] = "1.5.0.1"
(root / "package.json").write_text(json.dumps(pkg, indent=2) + "\n")
# Keep npm ci's package metadata in sync; dependencies and integrity pins are unchanged.
lock = json.loads((root / "package-lock.json").read_text())
lock["version"] = pkg["version"]
lock["packages"][""]["version"] = pkg["version"]
(root / "package-lock.json").write_text(json.dumps(lock, indent=2) + "\n")
print("Android overlay applied to", PIN)
