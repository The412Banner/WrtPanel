#!/bin/sh
# WrtPanel CI test router: OpenWrt 25.12.6 x86-64 rootfs in Docker with LuCI on :8088.
# procd/netifd objects are replayed from replay.json (generic, sanitised data).
set -e
mkdir -p /var/lock /var/run/ubus /var/state /tmp/resolv.conf.d /tmp/luci-modulecache /tmp/luci-indexcache
apk update >/tmp/apk.log 2>&1 || true
apk add luci luci-app-ksmbd ksmbd-server block-mount luci-app-firewall luci-proto-ipv6 luci-proto-ppp >>/tmp/apk.log 2>&1 || true
cp /ci/config/network /ci/config/wireless /ci/config/system /etc/config/
sed -i "s|list listen_http '0.0.0.0:80'|list listen_http '0.0.0.0:8088'|" /etc/config/uhttpd
for b in /sbin/sysupgrade /sbin/reboot /sbin/poweroff /sbin/halt /sbin/firstboot /sbin/jffs2reset /sbin/mtd /bin/kill; do
	[ -e "$b" ] || [ -L "$b" ] && rm -f "$b"
	printf '#!/bin/sh\necho "$(date +%%s) %s $*" >> /tmp/stub-calls.log\nexit 0\n' "$b" > "$b"; chmod 755 "$b"
done
printf 'wrtpanel\nwrtpanel\n' | passwd root >/dev/null 2>&1
ubusd -s /var/run/ubus/ubus.sock &
sleep 1
rpcd -s /var/run/ubus/ubus.sock -t 30 &
sleep 1
logd -S 64 &
ucode /ci/replay.uc &
sleep 1
logger -t wrtpanel-ci "test router started"
exec uhttpd -f -h /www -r OpenWrt -x /cgi-bin -u /ubus -U /var/run/ubus/ubus.sock -t 60 -T 30 -k 20 -A 1 -n 3 -N 100 -p 0.0.0.0:8088
