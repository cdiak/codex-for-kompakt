# Private remote access

Tailscale joins the Mac and Kompakt to the same private network. The phone can use home Wi-Fi, another Wi-Fi network, or mobile data. Codex continues running on the Mac. The phone keeps the same tasks, chats, review, and approval interface; it does not need an SSH client.

This is remote access to the companion's configured coding project. It does not add screen sharing or import the desktop Codex app's chats.

## Install and connect Tailscale

1. Create a personal account at [Tailscale](https://login.tailscale.com/start). GitHub sign-in works; use the same account on both devices. The identity provider does not automatically configure SSH.
2. Install the [standalone Mac app](https://tailscale.com/download/mac), sign in, and allow the required network extension/VPN configuration.
3. Sideload the official Android APK from [Tailscale's stable packages](https://pkgs.tailscale.com/stable/), sign in, and allow Android's VPN connection. Version 1.104.5 was used on Android 12 for this prototype.
4. Leave Tailscale connected on both devices. No exit node or router port forwarding is needed.

Only join devices/accounts you trust to this network. The companion additionally requires its own credential and pinned TLS certificate. Do not enable Tailscale Funnel, which would publish a service outside the private network.

## Expose only the companion port to the private network

The Python server continues listening on localhost. Tailscale Serve forwards raw TCP, preserving the existing end-to-end TLS connection:

```sh
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
"$TS" serve --bg --tcp=39443 tcp://127.0.0.1:39443
"$TS" serve status
"$TS" ip -4
```

The status must say **tailnet only**. Note the Mac's `100.x.x.x` address. The Serve configuration persists across Tailscale restarts.

## Pair the private address over USB

Stop an idle companion before rotating its certificate. Pair again afterward on every paired phone. Existing pairing tokens and chats are preserved when reusing the same runtime.

```sh
python3 setup_pairing.py \
  --runtime work/kompakt-runtime \
  --remote-ip YOUR_MAC_TAILSCALE_IP \
  --rotate-certificate \
  --adb work/toolchain/android/platform-tools/adb \
  --apk dist/Codex.apk
```

`--remote-ip` accepts only Tailscale's private IPv4 range. The new certificate covers localhost and the private address; Android still checks the hostname. Credentials pass through ADB standard input, never command arguments or the APK. The phone defaults to Remote after this pairing. Later app updates retain the selected connection and chat drafts.

For a manual companion:

```sh
KOMPAKT_WORKSPACE=/absolute/path/to/project './Start Remote Companion.command'
```

This keeps the Mac awake until stopped with Control-C; the display may sleep. Set `KOMPAKT_RUNTIME` if the runtime is elsewhere.

Run either the manual companion or the login service below, not both at the same time.

## Optional automatic startup

The installer creates one per-user LaunchAgent, copies the companion into `~/Library/Application Support/CodexForKompakt`, starts it at login, restarts it after failure, and prevents idle system sleep while it runs. It does not change global sleep settings. The Mac must be powered on and logged into this user account, and Tailscale must be connected.

Use runtime and project folders outside macOS-protected Documents/Desktop/Downloads directories. A background service may lack access to those folders even when an interactive Terminal can read them. For a fresh setup, prepare/pair using an Application Support runtime from the beginning and select a project under a development directory such as `~/Developer`. Existing setups should preserve their runtime and project together; the companion intentionally rejects a runtime belonging to another project.

```sh
python3 tools/install_mac_service.py \
  --runtime "$HOME/Library/Application Support/CodexForKompakt/runtime" \
  --workspace /absolute/path/to/project \
  --codex /absolute/path/to/codex
```

Use `--write-only /path/to/preview.plist` to inspect the configuration first. The source runtime must already contain the certificate and pairing token. Rerun the installer after updating `companion.py` to refresh its installed copy. Logs are private files in the runtime directory; they are not uploaded by this project.

Stop the automatic companion:

```sh
launchctl bootout "gui/$(id -u)/org.kompakt.codex.companion"
```

To disable future login startup too, remove `~/Library/LaunchAgents/org.kompakt.codex.companion.plist`. To disable remote forwarding:

```sh
/Applications/Tailscale.app/Contents/MacOS/Tailscale serve --tcp=39443 off
```

Stopping the companion does not delete chats, project files, or pairing credentials.

## Verify and use

1. Remove any existing ADB tunnel with `adb reverse --remove tcp:39443`.
2. In Codex, select **Connection → Remote · Wi-Fi or mobile data**. Confirm the status says **Ready … Remote** over Wi-Fi.
3. Unplug USB, turn Wi-Fi off, and submit a small coding task over mobile data. Review and approve its actions on the phone; confirm the result on the Mac.
4. Restore Wi-Fi if desired. Tasks keep running on the Mac while the phone is disconnected; returning to Codex fetches current results and pending approvals. Actions are never automatically approved.

If disconnected, check Tailscale on both devices, the Mac's internet/power, and the companion service. Android's **Always-on VPN** option can help keep Tailscale connected; after a phone restart, verify it is connected before opening Codex. Notifications and battery endurance have not been validated. Key expiry or removal of a device from Tailscale can require signing in again.

For USB fallback, run `adb reverse tcp:39443 tcp:39443` and select **USB cable**. Changing networks does not require re-pairing as long as the Mac's Tailscale address and certificate remain the same.

References: [Tailscale TCP forwarding](https://tailscale.com/docs/reference/tailscale-cli/serve), [GitHub identity setup](https://tailscale.com/docs/integrations/identity/github), [Android installation](https://tailscale.com/docs/install/android).
