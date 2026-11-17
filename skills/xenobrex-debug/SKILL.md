---
name: xenobrex-debug
description: Use when a user's XenoBrex (aRacer ECU tuning tool) misbehaves - a tune will not open, the ECU will not connect, live tuning does not stick, or read-back differs. Collect facts with xbrex, interpret them, and give fix steps.
---

# Debugging XenoBrex

XenoBrex (XB) is an unofficial aRacer ECU tuning tool: the desktop app edits tune files (.amx and others), and it can tune live over the ECU's Wi-Fi.
`xbrex` is the command-line tool installed with it. This guide shows how to use it to find a user's problem.

## Rules first

1. **Never write to the ECU without the user's explicit consent.** `xbrex ecu save` writes a whole calibration to the ECU; a wrong value can stop the engine from starting or damage it.
   The read-only commands (`info`, `read`, `compare`, `snapshot`) answer most questions. If a write is really needed, review the whole calibration with the user first: state which tables and cells would change, and why, and wait for the user to agree.
   `xbrex ecu save` always reads the whole calibration first, refuses an incomplete read-back or a version/platform mismatch, and saves a verified backup before writing. Without `--yes` it only reads, backs up and lists the tables and cells that would change, so the dry run is safe to use for investigation. For a whole-calibration write, go through every listed change with the user; do not treat `--yes` as a formality.
2. **Never print, paste or send the keys.** `xbrex doctor` only says whether keys exist; do not read `keys.properties`.
3. **Tune files and ECU backups are the user's data.** Read them to investigate, but do not upload them anywhere.
4. **Do not work on the ECU while the engine is running or the bike is being ridden**, unless the user says so. A full read-back holds the connection for a minute or two.
5. Report results as they are: if nothing was found, say so; if a command failed, paste the error verbatim.

## Step 1: environment

```
xbrex doctor
```

It lists the version, Java, the settings folder, the definitions folder (which ControlFiles it holds, whether ScaleData is there), whether keys exist, the log file, and the number of ECU backups.

| You see | Meaning | Fix |
|---|---|---|
| Definitions folder missing, or no ControlFile_xx | Tunes for that platform cannot be opened | In the desktop app, choose the definitions folder or import it from an XTune package |
| ScaleData missing | No tune can be opened | Same as above |
| Keys missing | Encrypted files (most .amx) cannot be opened | `xbrex import <the user's XTune package>` (same as the desktop app's import: keys, plus definitions when no folder of their own is set), or `xbrex keys -` (two lines on stdin) / `xbrex keys --file <key file>`; `xbrex keys <key1> <key2>` also works but may enter shell history |

## Step 2: log

```
xbrex log          # last 80 lines
xbrex log 300
```

The desktop app writes the connection sequence, ECU replies and errors here (never keys, passwords or tune contents). Start with the lines around the last failure or `[error]`.

## Step 3: by symptom

### A tune will not open

```
xbrex info <tune>
```

- "keys are required": see the table above.
- "Cannot decrypt this file": wrong keys, or not an aRacer tune (UMA M9 is not supported).
- "No ControlFile_xx in the definitions folder": the definitions for that platform are missing.
- It opens but reports size mismatches or missing axes: the definitions do not match the tune's version; the user needs the matching definitions.

### The ECU will not connect

The computer must be on the ECU's Wi-Fi first. Default address `192.168.88.88:6666`; the factory password is 888888 (override with `XB_ECU_PASSWORD`).

```
xbrex ecu info 192.168.88.88
```

| You see | Meaning |
|---|---|
| connect timed out / refused | Not on the ECU's Wi-Fi, or wrong address |
| login no reply | The ECU did not answer. Common causes: idle stop (ISS) is active, or the ECU has just powered up. Turn the key off and on, wait a few seconds, retry |
| login rejected | Wrong password |
| Wrong platform (e.g. Mini X instead of Super XX) | The ECU heartbeat was not heard. Reconnect |

`info` prints the ECU version as `V S C A`.

### The desktop app says the versions differ and live tuning is not allowed

The ECU's V and C differ from the tune's (XTune checks the same two). This is not a bug: the user opened the wrong file and needs the tune for this ECU.
Moving across versions means converting the file (`xbrex convert`), not forcing a write.

### Connected, but "values on the ECU differ from the file"

```
xbrex ecu compare 192.168.88.88 <tune>
```

It lists the tables and cells that differ (file value -> ECU value). A few differing cells are normal: the ECU rewrites some tables at power-up (for example the launch level), and some are learned values.
Many differences mean this file is not what is on the ECU. Let the user decide between keeping the ECU's values and writing the file to the ECU; do not choose for them.

### Live tuning: a table is marked as rejected by the ECU

The ECU replied with an abort code. Common ones:

| abort | Meaning |
|---|---|
| 0x06010004 | The feature for this table is not enabled on this ECU (aRacer feature licensing, e.g. TC on Mini X). Only the official app can enable it |
| 0x06010003 | Login required |
| 0x06010002 | Read-only |
| 0x06020000 | The table does not exist on the ECU (different version) |
| 0x06090011 | The table has a different number of rows on the ECU (different version) |

### Live tuning: a table is marked as changed by the ECU

The write succeeded and read back correctly, but the ECU later changed the value back. Usually the firmware does not accept the value (above its own limit), or the ECU maintains that table itself (the eBoost time without an MGU is one). It is not a connection problem; resending gives the same result.

### Live tuning: a table is marked as out of range

The edited cells are outside the definition's Min to Max and were not sent. Bring them back in range and they will be sent.

### Values changed live are gone after the key is turned off

Live tuning writes to the ECU's RAM. When the key is turned off the ECU usually saves the calibration to flash itself, but not every time (it depends on the ECU's state).
To make sure the change stays, the user should save once with the official app or XB's save-to-ECU function. A bad change can be restored from a backup (next section).

## Converting a tune to another version or platform

```
xbrex stock ASX2 DRG                          # vehicles in the official list
xbrex convert <in> ASX2 <out> [--table-id <vehicle>] [--yes]
```

Use `--carry <ids>` / `--baseline <ids>` to change the choice for listed tables once the user has decided. `convert` first downloads the target vehicle's latest stock tune from the official server as the baseline (md5 checked); without it nothing is converted. It prints what was carried, what kept the baseline, which learned values were cleared, and a review list. A target vehicle different from the input must be confirmed (y/n, or `--yes`). Pass the review list on to the user; do not decide it for them.

## Backups

Every time the desktop app connects to the ECU, it saves the ECU's values to `<settings folder>/ecu-backups/` as `ECU_<time>_<original name>`.
A backup can also be made by hand:

```
xbrex ecu snapshot 192.168.88.88 <tune> [out]
```

This is the complete calibration on the ECU at that moment and can be opened in the desktop app.

To restore one (only with the user's consent; it writes the ECU):

```
xbrex ecu save 192.168.88.88 <backup file>          # dry run: what would change
xbrex ecu save 192.168.88.88 <backup file> --yes    # write it
```

`save` refuses a tune whose version or platform differs from the ECU. After writing it reads the ECU back; tables that still differ are ones the ECU adjusts itself (e.g. the launch level) or features not enabled on that ECU.

## Projects (.xbx) and ride logs (.xbl)

An `.xbx` is a XenoBrex project: the tune (same content as the official file, named by platform: `.amx` Mini X, `.asx` Super X, `.amx2`/`.asx2` X2, ...), vehicle notes, a change log with every cell before and after, every ECU read-back (same content stored once), and conversion reports. Every command that takes a `<tune>` also takes an `.xbx`; saving to an `.xbx` adds to its change log. `ecu snapshot` and `ecu save` on a project put the ECU read-back into the project instead of the backup folder.

```
xbrex project info <p.xbx>                    summary, ECU read-backs, conversion reports
xbrex project log <p.xbx> [n] --cells         who changed which cell, from what to what
xbrex project export <p.xbx> [out]            the tune, for XTune / STX
xbrex project export <p.xbx> --backup <n>     ECU read-back #n (to restore with ecu save)
```

An `.xbl` is a ride log packed by ReX (it can be opened as a ZIP). It holds the raw frames, the loga, events, every write the app sent to the ECU, and the vehicle model.

```
xbrex ride info <ride.xbl> [--lookup]         vehicle, times, writes (--lookup: vehicle name from the official list)
xbrex ride writes <ride.xbl>                  each write with the ECU's answer (ack / abort / no reply)
xbrex ride export <ride.xbl> --loga [out]     the loga for XTune
```

A project or ride log that fails its checksum is damaged; do not try to repair it by hand, ask for the original.

## Other commands

```
xbrex list <tune> [level]              list tables
xbrex show <tune> <table id or name>   show one table
xbrex diff <fileA> <fileB>             compare two files
xbrex ecu read <address> <index> [sub] read one object (index in hex, table = 0x3000 + table id, sub = row, zero-based)
```

## Reporting to the developers

Ask the user to open a GitHub issue with: the output of `xbrex doctor`, the relevant part of `xbrex log`, the ECU platform and version (`ecu info`), what they did, what they expected, and what they saw.
Never attach keys; ask the user before attaching a tune file.
