# AOHP units — systemd/dinit-style service management for containers

Status: implemented 2026-10-05 (aohp-containerd + IAohpContainer.unitControl + AOHP Driver 0.4.0 +
aohp CLI `unit`/`timer` + container-side `systemctl`/`journalctl` shims + template units).

## Why

Android has no PID namespaces in the GKI kernels our phones run (CONFIG_PID_NS unset), so a real
systemd/dinit as PID 1 inside the container is impossible. Until now the only "service" concept was
containerd's `startService(env, id, command)`: a detached `sh -c` with a log file, no restart, no
ordering, no timers; the Driver's ServiceRegistry (0.3.0) replayed those commands at boot. On chex the
same software runs under systemd (openclaw-gateway.service with Restart=on-failure, a watchdog
timer, sshd, networking). Units bring that subset to the container, supervised by aohp-containerd —
the process that already forks every container process.

## User-facing surface

### Unit files (inside the container rootfs)

```
/etc/aohp/system/<name>.service
/etc/aohp/system/<name>.timer
/etc/aohp/system/aohp.target.wants/<name>.(service|timer)   -> ../<name>...   (enabled = symlink exists)
/etc/aohp/env-name                                            (written by containerd: this env's name)
```

INI, a **strict systemd subset**. Unknown keys and sections → warning in the unit's `loadWarnings`,
never an error. Unsupported *values* of supported keys (e.g. `Type=notify`) → load error, the unit
shows `loadState=error` and cannot start.

| Section | Key | Supported |
|---|---|---|
| [Unit] | Description | yes |
| | After, Before | ordering only (topological; a cycle is a load error for every unit in it) |
| | Requires | pulled in on start; if the dependency fails to start the unit fails; when a Requires dependency stops or fails the dependent is stopped |
| | Wants | pulled in on start, best effort |
| | ConditionPathExists | path inside the rootfs, `!` negation; false → unit is skipped (inactive, result `condition`), not failed |
| [Service] | Type | `simple` (default), `oneshot`; `forking`, `notify`, `exec`, `dbus`, `idle` → error |
| | ExecStartPre, ExecStart (required), ExecStartPost, ExecStop, ExecReload | one line each (repeat ExecStartPre/Post allowed, run in order); each runs as `/bin/sh -c` in the container; leading `-` ignores the exit status |
| | RemainAfterExit | yes/no (oneshot: stays `active (exited)`) |
| | Restart | `no` (default), `always`, `on-failure`, `on-abnormal`, `on-success` |
| | RestartSec | seconds or `5s`, `2min`, `500ms`; default 1s |
| | StartLimitBurst, StartLimitIntervalSec | default 5 in 60 s → `failed (start-limit-hit)`; applies to automatic restarts; a manual `start` resets the counter |
| | SuccessExitStatus | extra exit codes (and signal names) counted as clean, e.g. `0 143` |
| | Environment | `A=b "C=d e"`, repeatable; merged over containerd's default env (PATH/HOME/TERM/LANG); **no `--jitless` is injected for units** |
| | EnvironmentFile | path in the rootfs, `-` prefix = optional; `KEY=VALUE` / `export KEY=VALUE` lines, quotes stripped |
| | WorkingDirectory | path in the rootfs (default `/`), `-` prefix = ignore if missing |
| | TimeoutStopSec | SIGTERM → wait → SIGKILL; default 30 |
| | TimeoutStartSec | ExecStartPre/oneshot budget; default 90 |
| | KillMode | `control-group` (default; = the service's process group — containerd already gives each service its own pgid), `process` (main pid only) |
| | User, Group, Nice, Limit*, Sockets, StandardOutput… | ignored with a warning (everything runs as root; stdout/stderr always go to the unit log) |
| [Timer] | OnBootSec | relative to **env start** |
| | OnUnitActiveSec | relative to the last activation of the triggered service |
| | OnCalendar | `minutely`, `hourly`, `daily`, `weekly`, `*-*-* HH:MM:SS`, `HH:MM`; anything else → error |
| | Unit | service to trigger (default `<name>.service`) |
| | Persistent | yes: last trigger stamped under the env dir; a missed OnCalendar/OnUnitActiveSec fires at next env start |
| | RandomizedDelaySec, AccuracySec | ignored with a warning |
| [Install] | WantedBy | `aohp.target` (anything else → warning; enable still links into aohp.target.wants) |

Not implemented (known gaps): socket activation, `Type=forking`/`notify`/sd_notify, `User=`, cgroup
resource keys per unit (the env's cgroup applies), `OnCalendar` beyond the subset, `systemctl edit`,
`PartOf`/`BindsTo`/`Conflicts`, templates (`@`). Timers are not persistent across an env stop unless
`Persistent=yes`.

### Exec environment

Each Exec line runs `/bin/sh -c '<line>'` in a fresh mount namespace with the usual container bind
mounts, chrooted into the env, in its own session + process group, stdout/stderr appended to
`/data/aohp/envs/<env>/.aohp/log/<unit>.log` (rotated to `.log.1` when > 1 MB at (re)start). The
daemon's fds are closed. Environment: containerd defaults + `Environment=` + `EnvironmentFile=`,
plus `AOHP_ENV=<env>`, `AOHP_UNIT=<name>`.

### States (systemd vocabulary)

`active`/`activating`/`deactivating`/`inactive`/`failed` with sub-states `running`, `exited`,
`dead`, `auto-restart`, `start-pre`, `start-post`, `stop-sigterm`, `stop-sigkill`, `condition`,
`waiting` (timers). `result`: `success`, `exit-code`, `signal`, `timeout`, `start-limit-hit`,
`dependency`, `resources`, `condition`. Clean exit = code in SuccessExitStatus (default 0) or
killed by SIGHUP/SIGINT/SIGTERM/SIGPIPE.

### Env start / stop

"Starting an env" = starting every enabled unit (symlinks in aohp.target.wants) in dependency order;
"stopping an env" = stopping every active unit in reverse order. containerd does **not** start envs by
itself at device boot: the OpenClaw launcher wrapper reads its provider key through the Driver bridge,
so the Driver's boot receiver starts the bridge first and then issues env-start for every env whose
Autostart switch is on (default on since 0.5.0; the switch is an opt-out). Envs without any unit fall back to the 0.3.0 ServiceRegistry replay.

### Legacy startService / stopService / listServices / serviceLog

Kept, mapped onto units: `startService(env, id, cmd)` starts `<id>.service` if such a unit file is
loaded (the command argument is then ignored — so the Driver 0.3.0 "Start gateway" button and
`aohp sandbox svc-start -i openclaw-gateway` now run the template's supervised unit), otherwise it
creates a **transient unit** named `<id>` (`Type=simple`, `Restart=no`, like `systemd-run`).
`listServices` returns every unit in the old JSON shape; `serviceLog` reads the unit log.

## Protocol

containerd socket: `UNIT <env> <op> <base64(json-args)>` → `OK <json>` | `ERR <message>`.
Binder: `IAohpContainer.unitControl(String env, String op, String jsonArgs) → String json` (one generic
method; appended last in the AIDL so transaction codes of older methods are unchanged; an `{"error":…}`
object is returned on daemon errors). Bridge (ws :6666): `sandbox.unit` with `{name, op, ...args}` and
aliases `sandbox.unit_<op>` (`sandbox.unit_list`, `sandbox.unit_start`, …).

| op | args | result |
|---|---|---|
| list | — | `{units:[UnitJson…]}` |
| status | unit | UnitJson (+ `log` tail 2 KB) |
| start, stop, restart, reload | unit | UnitJson after the operation (reload = ExecReload; falls back to restart when absent) |
| enable, disable | unit, now? | UnitJson (`now` also starts/stops) |
| daemon-reload | — | `{units:[…], warnings:[…]}` |
| log | unit, tailBytes (default 8192) | `{log, size}` |
| cat | unit | `{path, text}` |
| timers | — | `{timers:[TimerJson…]}` |
| env-start, env-stop | — | `{units:[…], started|stopped:[…], failed:[…]}` |
| reset-failed | unit? | UnitJson / list |

UnitJson: `{name, type: service|timer, description, loadState: loaded|error|transient, loadError,
loadWarnings[], path, enabled, active, sub, result, mainPid, exitCode, exitSignal, nRestarts,
activeEnterTime, inactiveEnterTime, condition, execStart, restart, restartSec, after[], before[],
requires[], wants[], timer?: {unit, nextElapse, lastTrigger, onBootSec, onUnitActiveSec, onCalendar,
persistent}}` (times are epoch seconds, 0 = never).

Direct debugging without the framework: `aohp-containerd --client 'UNIT oc list e30='` (root; connects
to the daemon socket, or `--socket PATH`).

## CLI and shims

`aohp unit <env> list|status <u>|start|stop|restart|reload|enable [--now]|disable [--now]|daemon-reload|
log <u> [-n BYTES]|cat <u>|env-start|env-stop|reset-failed [u]` and `aohp timer <env> list`.
Human-readable (systemctl-like) output by default, `--json` for the raw result.

Inside the container `/usr/local/bin/systemctl` (POSIX sh) maps `start stop restart reload status
enable disable is-active is-enabled is-failed daemon-reload list-units list-timers cat reset-failed
--now` onto `aohp unit "$(cat /etc/aohp/env-name)" …`, and `/usr/local/bin/journalctl -u X [-n N]`
prints the unit log. **They are shims**: no D-Bus, no `--user`, no `edit`, no `-f`; they exist so
muscle memory and existing scripts (and agents that reach for `systemctl`) work. On Debian the real
`/usr/bin/systemctl` (pulled in by openssh-server) is shadowed by PATH order.

## Driver 0.4.0

Harness tab → **Units** card per env: name, description, state badge, sub-state, uptime / next elapse
for timers, restarts, buttons start/stop/restart/enable/disable, Log (same log view), daemon-reload
and env-start/env-stop actions. The Gateway card drives `openclaw-gateway` as a unit when the env has
one. Autostart at boot = env-start (registry replay only for envs without units).
