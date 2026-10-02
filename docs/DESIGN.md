# AOHP Driver — design

A replacement for the AOHPAgentDriver demo app, organised around four areas:
Runtime · Harness · Terminal · Web. Client-only: it talks to the AOHP framework
services over Binder and does **not** host the ws://:6666 agent bridge (the
stock AOHPAgentDriver keeps doing that for the agent inside the container).

## Facts this design rests on (verified 2026-10-02 on OnePlus 13 / AOHP GSI)

- Framework services (all `enforceCallingOrSelfPermission(MANAGE_AOHP_VIRTUAL_DISPLAY)`,
  protectionLevel `signature|privileged`):
  `aohp_container`, `aohp_virtual_display`, `aohp_agent_view`, `aohp_event_stream`,
  `aohp_file_bridge`, `aohp_security_bridge`, `aohp_ad`.
  AIDL sources: `/aosp/aohp/AOSP/frameworks/base/core/java/com/android/internal/aohp/*.aidl`
  (copies also in AOHPAgentDriverApp/app/src/main/aidl/).
- Signing the APK with the tree's platform key
  (`/aosp/aohp/AOSP/build/make/target/product/security/platform.{pk8,x509.pem}`)
  grants signature-level permissions **and** hidden-API access
  (`isSignedWithPlatformKey()`), so `Class.forName("android.os.ServiceManager")
  .getMethod("getService")` works and the app can be plain `adb install`-ed —
  no priv-app allowlist, no reflash.
- `IAohpContainer`: listContainers, createContainer(name, template), destroyContainer,
  resetContainer, execSync(name, cmd, timeoutMs) → JSON, openShell(name) → PFD,
  templateInfo, startService(name, id, cmd) → pid, stopService, listServices → JSON,
  serviceLog(name, id, tailBytes), diagnose(name) → JSON.
- openShell's PFD is a **socket relay** to a pty master held by aohp-containerd
  (forkpty). The app cannot TIOCSWINSZ it. v1 workaround: after connect send
  `stty cols C rows R` (and again on resize). Proper fix later: `resizeShell`
  in containerd+framework+AIDL (we already carry system/core + sepolicy forks).
- OpenClaw gateway inside the container listens on 127.0.0.1:18789 with
  `gateway.auth.mode=none`, and the container shares the host network namespace
  (containerd only unshares CLONE_NEWNS) → the Android-side WebView can load
  http://127.0.0.1:18789/ directly (verified: adb forward → HTTP 200).
- Toolchain on chex: `/aosp/android-sdk` (platforms 36, build-tools 36.0.0), Java 21,
  Gradle 8.7 wrapper, AGP 8.5.1 known-good. `~/.gradle` cache is warm (800 MB).
- Container paths on device: `/data/aohp/envs/<name>/{rootfs,services}`,
  templates `/data/aohp/templates/<tpl>.tar.gz`. Our Debian env is named `oc`
  (template `debian-oc`), harness bootstrap = `aohp-bootstrap <user>/<repo>`
  from https://github.com/injinj/aohp-agents ; secrets via `aohp-secrets`
  (age | keystore | paste).
- The stock app's service id for the gateway is `openclaw-gateway`, command
  `openclaw gateway` (our launcher wrapper handles --jitless + secrets).

## App structure

Kotlin, Jetpack Compose (Material 3), single Activity, bottom navigation with
four destinations. Package `org.aohp.driver`, app name "AOHP Driver",
minSdk 34, compileSdk/targetSdk 36.

```
app/src/main/aidl/com/android/internal/aohp/   copied AIDL (IAohpContainer, IAohpVirtualDisplay, IAohpEventStream)
app/src/main/java/org/aohp/driver/
  binder/ServiceManagerCompat.kt    reflection getService()
  binder/ContainerService.kt        typed Kotlin wrapper over IAohpContainer (suspend funs, JSON parsing)
  binder/VirtualDisplayService.kt   list/snapshot/destroy (status only)
  runtime/   RuntimeScreen + RuntimeViewModel     envs: list/create/reset/destroy/diagnose, cgroup stats, template info
  harness/   HarnessScreen + HarnessViewModel     per env: svc list/start/stop/log, gateway health (HTTP GET :18789), bootstrap action
  terminal/  TerminalScreen + PtySession          WebView + bundled xterm.js, bytes <-> openShell PFD, stty-resize hack
  web/       WebScreen                            WebView → http://127.0.0.1:18789/ (Control UI); JS enabled, DOM storage, loopback only
  ui/        theme, shared composables
```

### Why these choices (for non-Android readers)

- **Activity** = one window/screen host. **Fragment/Composable** = a piece of UI
  inside it. We use Compose (declarative UI in Kotlin) instead of XML layouts —
  less boilerplate, easier for an agent to maintain.
- **ViewModel** = the object that owns a screen's state and survives rotation.
  All Binder calls happen off the main thread inside ViewModels (coroutines on
  Dispatchers.IO); the UI just renders a `StateFlow`.
- **Binder** = Android's IPC. `IAohpContainer.Stub.asInterface(binder)` gives a
  typed proxy; calls are synchronous RPCs into system_server.
- **WebView** = embedded Chromium. Used twice: xterm.js terminal, and the
  OpenClaw Control UI. Both load only loopback/asset URLs.
- **Foreground service**: deliberately **none** in v1. The app is a viewer;
  nothing has to outlive the screen. (The 6-hour FGS kill that bites the stock
  app's bridge is therefore not our problem.)

### Terminal

`assets/term/index.html` loads xterm.js + fit addon (vendored from npm
@xterm/xterm, @xterm/addon-fit). Kotlin side: `PtySession` owns the
ParcelFileDescriptor from `openShell`, a reader thread pushes bytes to JS via
`evaluateJavascript("term.write(<base64>)")` (batched ~16 ms), and a
`@JavascriptInterface` object receives keystrokes/paste and writes them to the
fd. On fit/resize JS reports cols/rows; Kotlin sends `stty cols C rows R\n`
(known gap: echoes in the shell until containerd grows resizeShell).
One session per env, kept alive across tab switches while the Activity lives.

### Harness screen

Per selected env, shows: gateway service state (from listServices + HTTP probe
of 127.0.0.1:18789 → up/down + version from /health if present), buttons
Start/Stop/Restart (startService "openclaw-gateway" "openclaw gateway"), Logs
(serviceLog tail, auto-refresh), "Bootstrap…" (execSync of
`aohp-bootstrap <repo>` with the repo entered in a dialog; prefill
`injinj/aohp-config-chris`), "Secrets" (list names only via
`aohp-secrets list` if present). Nothing here stores secret values in the app.

### Runtime screen

Cards per env from listContainers + diagnose(name) (pids, cgroup cpu/mem,
template). Actions: Select (sets the app-wide selected env, persisted in
DataStore), Reset (confirm), Destroy (confirm, typed name), Create (dialog:
name + template from templateInfo/known list). Containerd health = whether
getService("aohp_container") is non-null and listContainers succeeds.

### Web screen

WebView on http://127.0.0.1:18789/ with JS, DOM storage, and
`setMixedContentMode` default; intercepts navigation to non-loopback hosts and
hands them to the system browser (OAuth callbacks come back to loopback and
stay inside). Pull-to-refresh and a "gateway down" placeholder that links to
the Harness screen.

## Build / install (headless, from chex)

```
export ANDROID_HOME=/aosp/android-sdk JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java))))
cd /aosp/aohp/aohp-app/aohp-driver && echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleDebug          # ~1 min warm
./scripts/sign-platform.sh app/build/outputs/apk/debug/app-debug.apk /tmp/aohp-driver.apk
adb -s e6d11a7c install -r -g /tmp/aohp-driver.apk
adb -s e6d11a7c shell am start -n org.aohp.driver/.MainActivity
```
`scripts/sign-platform.sh` wraps apksigner with the tree's platform key
(never copy the key into the repo). Gradle's own signingConfig stays debug.

## Out of scope for v1

UDA, ads, overlay, MediaProjection recording, File Bridge UI, the Contacts
"CLI contact test", hosting the ws bridge. Virtual displays appear only as a
read-only list on the Runtime screen.

## Roadmap after v1

1. `resizeShell` across containerd/framework/AIDL (+ PR upstream).
2. Move the ws bridge into a system service with a Unix socket bind-mounted
   into the rootfs; this app becomes a client of that too.
3. Harness plugins: Claude Code / Codex / OpenCode status alongside OpenClaw.

