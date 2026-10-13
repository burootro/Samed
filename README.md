# Samed (صامد)

A root Android app that keeps you connected when someone on the same Wi-Fi
tries to cut your internet with an **ARP-spoofing** tool such as NetCut.

## How it works

NetCut-style attacks don't touch your phone directly. They flood the local
network with forged ARP replies that say *"the router is at this fake MAC
address"*. Your phone believes it, sends its traffic to nowhere, and your
internet "dies".

Samed defeats this at the kernel level:

1. **Learn** the router's real MAC address once (from the current ARP table).
2. **Lock** it into the neighbour table as a **permanent** entry:
   `ip neigh replace <gw_ip> lladdr <gw_mac> dev <iface> nud permanent`.
   A permanent entry is *never* overwritten by incoming ARP replies — so the
   attacker's forged packets are simply ignored and your traffic keeps flowing.
3. **Watch** the entry every couple of seconds. If it's ever dropped,
   downgraded, or its MAC changed, Samed re-asserts the lock immediately and
   counts it as a blocked attempt.
4. When you switch Wi-Fi networks it re-learns and re-locks the new gateway
   automatically. When you stop protection it restores normal dynamic ARP.

Samed only reads and pins the ARP entry for the router **you are already
connected to**. It never sends forged packets and never touches any other
device on the network — it's purely defensive.

## Requirements

- A rooted device (Magisk, etc.). Samed asks for root on first start.
- `ip` (iproute2) — present on essentially all modern Android builds.

## Build

```bash
cd Samed && ./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

> Note: this archive ships the Gradle **wrapper config** but not the
> `gradle-wrapper.jar` binary. Open the folder in Android Studio once (it
> generates the wrapper jar automatically), or run `gradle wrapper` with a
> local Gradle install, then the `./gradlew` command above works.

## Package / app id

`ro.buroot.samed`
