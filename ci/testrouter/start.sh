#!/bin/sh
# Starts the CI test router container on 127.0.0.1:8088 and waits for the LuCI login page.
set -e
cd "$(dirname "$0")"
B=https://downloads.openwrt.org/releases/25.12.6/targets/x86/64
F=openwrt-25.12.6-x86-64-rootfs.tar.gz
curl -fsSLO "$B/$F"
echo "55e52bb34de582a1ebe56bd50f94ff54ac7c77f5bca9cae807656d1930456da4  $F" | sha256sum -c -
docker import "$F" wrtpanel-openwrt:25.12.6
docker run -d --name wrtpanel-router -p 8088:8088 -v "$PWD":/ci:ro wrtpanel-openwrt:25.12.6 /bin/sh /ci/init.sh
for i in $(seq 1 120); do
	code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/cgi-bin/luci/ || true)
	[ "$code" = "403" ] && { echo "test router up after ${i}s"; exit 0; }
	sleep 1
done
docker logs wrtpanel-router | tail -50
exit 1
