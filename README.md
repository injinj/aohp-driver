# AOHP Driver

A small Android app for driving an AOHP (Android Open Harness Project) device:
the Linux container runtime, the OpenClaw harness inside it, an interactive
terminal, and the OpenClaw Control UI — four bottom tabs.

It is a **client** of the AOHP framework services (`aohp_container`,
`aohp_virtual_display`) over Binder. It does not host the agent ws-bridge
(the stock AOHPAgentDriver keeps doing that) and has no foreground service.

| Tab | What it does |
|---|---|
| **Runtime** | containerd health, one card per env (template, rootfs, services, cgroup, usage), Select / Reset / Destroy (typed confirm) / Create (template picker), read-only virtual display list |
| **Harness** | OpenClaw gateway state (listServices + HTTP probe of 127.0.0.1:18789 and /health), Start / Stop / Restart, service list, log tail with auto-refresh, Bootstrap dialog (`aohp-bootstrap <user>/<repo>` with polled progress), secret *names* via `aohp-secrets list` |
| **Terminal** | `openShell` → xterm.js in a WebView; extra key row (Esc Tab Ctrl Alt arrows Home End PgUp PgDn …); one shell per env, survives tab switches |
| **Web** | WebView on http://127.0.0.1:18789/ (Control UI); JS + DOM storage; non-loopback links open in the system browser; "gateway down" placeholder |

See [docs/DESIGN.md](docs/DESIGN.md) for the design and the device facts it rests on.

## Requirements

- Device running the AOHP GSI (framework services present) with the AOSP
  tree's **platform key** — the APK must be signed with it to get
  `MANAGE_AOHP_VIRTUAL_DISPLAY` (signature-level) and hidden-API access.
  No priv-app allowlist and no reflash needed: plain `adb install`.
- Build host: Android SDK (platforms 36, build-tools 36.0.0), JDK 21, network
  for Maven on the first build. Gradle 8.7 wrapper, AGP 8.5.1, Kotlin 2.0.21,
  Jetpack Compose (BOM 2024.10.01).

## Build / sign / install

```bash
export ANDROID_HOME=/aosp/android-sdk
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
cd /aosp/aohp/aohp-app/aohp-driver
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew --no-daemon assembleDebug                      # ~1 min warm, several min cold
./scripts/sign-platform.sh app/build/outputs/apk/debug/app-debug.apk /tmp/aohp-driver.apk
adb -s e6d11a7c install -r -g /tmp/aohp-driver.apk
adb -s e6d11a7c shell am start -n org.aohp.driver/.MainActivity
```

Or all of the above in one go: `scripts/install.sh [adb-serial]`.

`scripts/sign-platform.sh` reads the key from
`/aosp/aohp/AOSP/build/make/target/product/security/platform.{pk8,x509.pem}`
(override with `AOHP_PLATFORM_KEYDIR`). The key is never copied into this repo;
Gradle's own signingConfig stays the default debug key.

Debugging: `adb -s e6d11a7c logcat -s AndroidRuntime:E AohpDriver:* chromium:I`
(`AohpDriver` is the single log tag; the Binder probe logs
`getService(aohp_container)` and `listContainers` at startup).

## Layout

```
app/src/main/aidl/com/android/internal/aohp/   IAohpContainer / IAohpVirtualDisplay / IAohpEventStream (copied from frameworks/base)
app/src/main/assets/term/                      xterm.js 6.0.0 + addon-fit 0.11.0 (vendored from npm) + index.html
app/src/main/java/org/aohp/driver/
  binder/     ServiceManagerCompat (reflection getService), ContainerService, VirtualDisplayService
  runtime/    RuntimeScreen + RuntimeViewModel
  harness/    HarnessScreen + HarnessViewModel
  terminal/   PtySession/PtySessionRegistry (fd I/O), TerminalScreen (TermBridge, TerminalView, TerminalHolder)
  web/        WebScreen (+ WebHolder: single app-wide WebView)
  ui/         theme, shared composables
scripts/      sign-platform.sh, install.sh
docs/         DESIGN.md
```

## Screenshots (verified on OnePlus 13, Android 16 AOHP GSI)

Captured with `adb -s e6d11a7c exec-out screencap -p > file.png`, stored in `docs/screenshots/`:

- `runtime.png` — containerd card, `oc` env card (selected), virtual displays
- `harness.png` — gateway UP / HTTP 200 / `/health live`, services, log tail
- `terminal.png` — shell in `oc`, extra key row, Ctrl-C and tab-switch survival
- `web.png` — OpenClaw Control UI loaded from 127.0.0.1:18789
- `create-dialog.png`, `create-progress.png`, `destroy-dialog.png` — throwaway env lifecycle
- `harness-scratch-eaddrinuse.png` — gateway started in a 2nd env; log tail shows EADDRINUSE (shared netns)
- `bootstrap-result.png` — Bootstrap dialog after a run (exit 127: tool absent in the fresh template)

## Known gaps (v1)

- **Resize**: the shell PFD is a socket relay, so the app cannot TIOCSWINSZ
  the pty. It sends `stty cols C rows R` after connect and on (debounced)
  size changes, which echoes in the terminal. Needs `resizeShell` in
  aohp-containerd + framework + AIDL.
- No `listTemplates` AIDL; templates are read directly from the world-readable
  `/system/etc/aohp/rootfs-templates/*.tar.gz`.
- `createContainer` is a single long Binder call (no progress); the UI just
  shows a spinner until it returns.
- The terminal's "Enter" is the IME's enter key; there is no Enter in the extra
  key row.
