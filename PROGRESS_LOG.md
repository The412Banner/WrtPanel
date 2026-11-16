# WrtPanel progress log

Newest entry first. Times are US Eastern (EST).

## 2026-11-16 — Project start: engine + app shell (feat/app, in progress)

**Why:** the user asked for a native Jetpack Compose app that manages an OpenWrt router 1:1 like
the LuCI web interface (every menu, tab and setting, updates, plugins), fluid / professional /
organised, with a built-in app updater reading this repo's latest release, signed with testkey
v1+v2+v3, public repo, without changing the user's router (X4S `nighthawk-nas`, OpenWrt 25.12.6).

**Decisions (made by me, user said "make your own decisions"):**
- Name WrtPanel, package `com.the412banner.wrtpanel`, GPL-3.0 + NOTICE for the LuCI (Apache-2.0) material.
- Native pages for everything in the router's menu; LuCI apps without a native page open in an
  in-app, signed-in LuCI view (so plugins never become unreachable).
- Same backend paths as LuCI: login form + sysauth cookie, ubus JSON-RPC (batched), dispatcher
  `admin/uci/apply_rollback|confirm|apply_unchecked|revert`, cgi-io helpers.
- Form engine modelled on form.js (Map / Typed / Named / Grid / Table sections, Value, ListValue,
  Flag, MultiValue, DynamicList, TextValue, Dummy, Button, FileUpload, SectionValue; depends,
  rmempty / optional / retain, grid parse only of editable non-modal options); validation.js
  ported 1:1.
- Builds: AGP 8.8.0 / Gradle 8.10.2 / Kotlin 2.0.21 (same as BFE CI), compileSdk/targetSdk 35,
  minSdk 26, AOSP testkey (`keystore/testkey.p12`, same file as Bannerlator).
- No local Android SDK on the device; builds only on GitHub Actions.

**Audit (read-only on the real router, 11-15 23:51 EST):** LuCI files copied with `tar` over SSH,
menu read with a normal LuCI login; readable sources from openwrt/luci @ 1c75ea46 (= router's
`26.317.52279~1c75ea4`). Visible menu: Status (Overview, Routing, Firewall, System Log / Kernel
Log, Processes, Channel Analysis, Realtime Graphs: Load / Bandwidth / Wireless / Connections),
System (System, Administration: Router Password / SSH Access / SSH-Keys / HTTP(S) Access / Repo
Public Keys, Software, Startup, Scheduled Tasks, Mount Points, LED Configuration, Backup / Flash
Firmware, Reboot), Services (Network Shares), Network (Interfaces, Wireless, Routing, DHCP, DNS,
Diagnostics, Firewall: General Settings / Port Forwards / Traffic Rules / NAT Rules / IP Sets).
Hidden by depends: Switch, routesj, iptables, Custom Rules, VPN.

**Test router (writes are tested here, never on the user's router):** `/root/work/wrtpanel-testrouter`
= official OpenWrt 25.12.6 armsr-armv8 rootfs (sha256 checked) under nested proot, same LuCI
packages/versions as the router, the router's /etc/config copied in (stays local), ubusd + rpcd +
logd + uhttpd on 127.0.0.1:8088 (root password `wrtpanel`), procd/netifd objects replayed from
read-only `ubus call`s of the router (`rootfs/root/replay-ubus.uc`). sysupgrade / reboot / kill /
firstboot / jffs2reset / mtd are logging stubs (the proot shares the phone's process list).
Start / stop: `start.sh` / `stop.sh` (stop uses bracket patterns so it cannot kill its caller).

**Built:** engine (LuciClient, UciStore, ChangesManager, Fs, Validation, FormRuntime, FormUi),
app shell (router list + login, gateway detection, cert pinning, drawer from the router menu,
tabs, Unsaved Changes sheet, apply status dialogs, plugin web view, app settings, updater with
daily WorkManager check + notification + PackageInstaller).

**Next:** first CI build, then the pages.

## Lessons learned (don't repeat these)

- `pkill -f '<pattern>'` from a shell whose own command line contains the pattern kills that
  shell (11-16 00:0x: exit 144). Use bracket patterns like `'[u]httpd -f'`.
