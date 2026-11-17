# XenoBrex

[繁體中文](README.md) | English

A desktop tuning tool for aRacer RC X series ECUs (Windows / macOS / Linux): open tune files, view, edit and compare tables, and tune live over the ECU's Wi-Fi.

**Unofficial** third-party tool, not affiliated with aRacer. It never changes passwords or flashes firmware.

## Features

- Open, edit, compare and save tune files (AMX / ASX / AMX2…): add, multiply, set, LINEAR, SMOOTH, undo; raw values can be edited directly
- Connect to the ECU: the whole calibration is read back and backed up first; edits are written about 1.2 s after you stop and read back to confirm; read/write whole tables; write the whole tune to the ECU; restore from the complete backup verified on this connection
- The map shows where the engine is running (live data from the ECU or from ReX on a phone)
- Function pages: idle, TPS learning, quick shifter, eBoost, backfire, auto warm-up, auto tune and more (depending on ECU model)
- Convert to another version or vehicle with the `xbrex convert` command: the target's stock file is downloaded from aRacer's official server as the baseline, and tables that need your decision are listed
- Sync server for [ReX](https://github.com/xeno-hf/ReX) on a phone: pairing code, encrypted connection; gives ReX tuning packs and receives recordings
- `.xbx` project files (tune + notes + change log + ECU backups) and `.xbl` ride recordings (inspect and export with `xbrex ride`)
- Command-line tool `xbrex` (English), installed together with the desktop app: version/vehicle conversion, `.xbl` inspection and export, ECU read-back and backups
- English, Traditional Chinese, Simplified Chinese, Japanese

## Install

Source is available now; the Windows `.msi`, macOS (Apple Silicon) `.dmg` and Linux `.tar.gz` installers are being prepared and will be published on [Releases](https://github.com/xeno-hf/XenoBrex/releases/latest).

Install from source (needs JDK 21):

```sh
git clone https://github.com/xeno-hf/XenoBrex.git
cd XenoBrex
./install.sh
```

The installers will not be signed; Windows and macOS will ask you to allow them on first launch.

## Definition files and keys

XenoBrex ships no aRacer calibration definition files (ControlFile) or keys; use your own:

- Import them from your own XTune (Android) package (`.xapk` / `.apks`). The files are only read; nothing in the package is run.
- Or choose a definition folder and paste the keys yourself.

The app does embed a monitoring-channel table (channel names, IDs, scaling, units and default channels) for live display, recording and playback only. It is not a calibration definition file and cannot be opened as one; see [NOTICE.md](NOTICE.md).

`sample/` has a hand-made demo set (not data from any ECU) to try the app:

```sh
XB_DEFINITIONS=sample/definitions ./gradlew :xb-app:run --args="sample/Demo.amx"
```

## Safety

- Connecting reads the whole calibration back and saves a backup first. Nothing is written if the tune version or model differs from the ECU; tables the ECU refuses are no longer sent to it (edits to them stay in the file only); you can write back the complete backup verified on this connection.
- Changing tables changes how the engine runs. Tune somewhere safe with the vehicle stopped; you are responsible for your settings.
- **All real-vehicle write and restore paths (live tuning, whole-calibration write, backup restore) are unverified on a real vehicle.** Before first use, confirm your backup and restore flow.

## Build

Requires JDK 21.

```sh
./gradlew build                # build and run tests
./gradlew :xb-app:run          # run
./gradlew :xb-app:packageMsi   # package; packageDmg on macOS, packageTarGz on Linux
```

## Modules

| Module | Contents |
|---|---|
| `xb-tune` | Tune core: reading, conversion, editing, saving, comparing, converting |
| `xb-container` | File layer: encryption, definitions, open/save, project files, official stock files |
| `xb-link` | ECU link: login, reads and writes, whole read-back and compare, live tuning |
| `xb-sync` | Sync with ReX |
| `xb-calui` | Function pages (shared with ReX) |
| `xb-cli` | Command-line tool `xbrex` |
| `xb-app` | Desktop app (Compose Multiplatform) |
| `rx-protocol`, `rx-defs`, `rx-session` | Protocol, live channels and sync shared with ReX |

## License

GPL-3.0; see [LICENSE](LICENSE). The Oxanium font is under the SIL Open Font License. See [NOTICE.md](NOTICE.md) for trademark notices.
