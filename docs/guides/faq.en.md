# FAQ

[繁體中文](faq.md)

### Is XenoBrex free? Is it related to aRacer?

It is free and open source under GPL-3.0. It is an unofficial third-party tool, not affiliated with or authorised by aRacer.

### Why do I have to supply the definitions and keys myself?

They belong to aRacer, so we don't distribute them. Import them from your own XTune installer; see [Getting started](getting-started.en.md#2-import-the-definitions-and-keys).

### Can it unlock functions aRacer has not enabled for my model?

No. XenoBrex does not unlock paid functions or tables that aRacer has not enabled for your model, and it never changes the password, flashes firmware or touches the activation codes.

"Show advanced tables" only lists tables the definition already has for your model that XTune doesn't show.

### Will aRacer detect it and lock my ECU?

We can't promise what aRacer will do in the future, but here is what we know:

- XenoBrex talks to the ECU the same way XTune does: same login, same table reads and writes.
- We have not found anything in the ECU that records which app wrote a value.
- Editing a tune file on the computer doesn't touch the ECU at all.

What we can't rule out is a future XTune or firmware update changing things, so keep the backup saved when you connect. If you want to be extra careful, edit files first and only write to the ECU when you are comfortable.

### "Connect ECU" is grey

Open a tune file first. See [Connect to the ECU](live-tuning.en.md#before-connecting).

### It can't connect to the ECU

- Is your computer on the ECU's Wi-Fi, and is the key on?
- Is XTune closed on your phone? The ECU accepts only one connection at a time.
- If you changed the password, enter that password.

### Is there a phone version?

Yes, [ReX](https://github.com/xeno-hf/ReX): live dashboard and recording, plus live tuning after pairing with XenoBrex (Android only for now).

### I have an idea or found a problem

- Questions, ideas, sharing: [Discussions](https://github.com/xeno-hf/XenoBrex/discussions)
- A confirmed bug: an [Issue](https://github.com/xeno-hf/XenoBrex/issues), with a screenshot and the log file (see [where the log is](live-tuning.en.md#if-ecu-recovery-required-appears)).
