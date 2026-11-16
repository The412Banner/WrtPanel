package com.the412banner.wrtpanel

import com.the412banner.wrtpanel.form.Datatypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    private fun ok(dt: String, v: String) = assertNull("$dt should accept '$v'", Datatypes.validate(dt, v))
    private fun bad(dt: String, v: String) = assertNotNull("$dt should reject '$v'", Datatypes.validate(dt, v))

    @Test fun ipv4() {
        ok("ip4addr", "192.168.1.1"); ok("ip4addr", "10.0.0.0/8"); ok("ip4addr", "10.0.0.0/255.0.0.0")
        bad("ip4addr", "256.1.1.1"); bad("ip4addr", "1.2.3"); bad("ip4addr(\"nomask\")", "10.0.0.0/8")
        ok("ip4addr(\"nomask\")", "192.168.0.1")
    }

    @Test fun ipv6() {
        ok("ip6addr", "fe80::1"); ok("ip6addr", "2001:db8::/32"); ok("ip6addr", "::"); ok("ip6addr", "::ffff:1.2.3.4")
        bad("ip6addr", "2001:db8:::1"); bad("ip6addr", "fe80::1/129")
    }

    @Test fun cidrAndRanges() {
        ok("cidr4", "0.0.0.0/0"); bad("cidr4", "1.2.3.4"); ok("cidr6", "::/0")
        ok("port", "65535"); bad("port", "65536"); ok("portrange", "1000-2000"); bad("portrange", "2000-1000")
        ok("range(64,9000)", "1500"); bad("range(64,9000)", "10")
        ok("and(uinteger,range(64,9000))", "1500"); bad("and(uinteger,range(64,9000))", "-5")
        ok("or(uinteger, string)", "main"); ok("or(uinteger, string)", "254")
        ok("iprange", "10.0.0.1-10.0.0.9"); bad("iprange", "10.0.0.9-10.0.0.1")
    }

    @Test fun hostAndNames() {
        ok("hostname", "openwrt"); ok("hostname", "router.lan"); bad("hostname", "-bad"); bad("hostname", "1.2.3")
        ok("host", "1.2.3.4"); ok("host", "example.com"); ok("host(0)", "0.openwrt.pool.ntp.org")
        ok("uciname", "lan_2"); bad("uciname", "lan-2"); ok("macaddr", "02:00:00:00:00:01"); bad("macaddr", "01:00:00:00:00:01")
        ok("wpakey", "12345678"); bad("wpakey", "1234567")
    }

    @Test fun lists() {
        ok("list(macaddr)", "02:00:00:00:00:01 02:00:00:00:00:02"); bad("list(macaddr)", "02:00:00:00:00:01 zz")
        ok("neg(ipaddr)", "!10.0.0.1")
    }

    @Test fun messages() {
        assertEquals("Expecting: valid port value", Datatypes.validate("port", "x"))
    }
}
