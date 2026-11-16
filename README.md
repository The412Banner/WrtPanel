# WrtPanel

An Android app for managing OpenWrt routers. It signs in like the LuCI web interface does
and shows the router's own menu (Status, System, Services, Network, …) as native
Jetpack Compose screens: the same pages, tabs and settings as the web interface, with the
same Save, Save & Apply (with automatic rollback), Apply unchecked and Revert behaviour.

Pages of LuCI apps (plugins) that WrtPanel has no native screen for open inside the app,
already signed in, so every menu entry of the router stays reachable.

## Status

Work in progress. No release yet; test builds are GitHub Actions artifacts.

## Requirements

- A router running OpenWrt with LuCI (developed against OpenWrt 25.12 / LuCI 25.12).
- Android 8.0 or newer.

## How it talks to the router

- Login: the LuCI login form (`/cgi-bin/luci`), session cookie `sysauth_http(s)`.
- Data and settings: ubus JSON-RPC on `/ubus` with the LuCI session, the same calls the
  web interface's views make (`uci`, `luci`, `luci-rpc`, `file`, `system`, `network.*`, …).
- Save & Apply: the LuCI dispatcher endpoints `admin/uci/apply_rollback`, `confirm`,
  `apply_unchecked` and `revert`, with the router's own rollback timeout.
- Files: `cgi-upload`, `cgi-download`, `cgi-backup`, `cgi-exec` (cgi-io).
- HTTPS with self-signed certificates: the certificate is shown once and pinned by its
  SHA-256 fingerprint.

## App updates

WrtPanel checks this repository's latest GitHub release when it starts and once a day in
the background, shows a notification when a newer version exists, and downloads and
installs it through Android's package installer. Test builds (pre-releases) are offered
only when enabled in the app settings.

## Building

GitHub Actions builds every push to `feat/**` and `fix/**` branches (workflow `Build`).
APKs are signed with the public AOSP test key (v1, v2 and v3 signatures) so that every
build installs over the previous one.

```
./gradlew assembleRelease
```

## Credits

- [LuCI](https://github.com/openwrt/luci) (Apache-2.0, © the LuCI contributors): page
  layouts, option names, labels, help texts and validation rules are ported from LuCI's
  views so the app matches the web interface. See `NOTICE`.
- OpenWrt is a registered trademark of Software Freedom Conservancy. WrtPanel is not
  affiliated with or endorsed by the OpenWrt project.

## License

GPL-3.0, see `LICENSE`. Parts ported from LuCI keep their Apache-2.0 notice (`NOTICE`).
