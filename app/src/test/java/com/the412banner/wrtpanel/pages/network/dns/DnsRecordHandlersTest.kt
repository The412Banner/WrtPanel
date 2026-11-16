package com.the412banner.wrtpanel.pages.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected values were produced by running LuCI's tools/dnsrecordhandlers.js (@1c75ea46) in node. */
class DnsRecordHandlersTest {
    private data class Case(val p: Int?, val t: String, val ps: List<String>, val hex: String?, val parsed: DnsRecordHandlers.Svcb?)

    private val cases = listOf(
        Case(1, ".", emptyList(), "000100", DnsRecordHandlers.Svcb(1, ".", emptyList())),
        Case(0, "foo.example.com.", emptyList(), "000003666f6f076578616d706c6503636f6d00", DnsRecordHandlers.Svcb(0, "foo.example.com.", emptyList())),
        Case(0, ".", emptyList(), null, null),
        Case(1, "foo.example.com.", listOf("port=53"), "000103666f6f076578616d706c6503636f6d00000300020035", DnsRecordHandlers.Svcb(1, "foo.example.com.", listOf("port=53"))),
        Case(
            1, "foo.example.com.", listOf("alpn=h2,h3", "ipv4hint=192.0.2.1,192.0.2.2", "ipv6hint=2001:db8::1,2001:db8::2", "port=8000"),
            "000103666f6f076578616d706c6503636f6d0000010006026832026833000300021f4000040008c0000201c00002020006002020010db800000000000000000000000120010db8000000000000000000000002",
            DnsRecordHandlers.Svcb(1, "foo.example.com.", listOf("alpn=h2,h3", "port=8000", "ipv4hint=192.0.2.1,192.0.2.2", "ipv6hint=2001:db8::1,2001:db8::2")),
        ),
        Case(
            16, "foo.example.org.", listOf("alpn=h2,h3-19", "mandatory=ipv4hint,alpn", "ipv4hint=192.0.2.1"),
            "001003666f6f076578616d706c65036f7267000000000400010004000100090268320568332d313900040004c0000201",
            DnsRecordHandlers.Svcb(16, "foo.example.org.", listOf("mandatory=alpn,ipv4hint", "alpn=h2,h3-19", "ipv4hint=192.0.2.1")),
        ),
        Case(
            1, "example.com.", listOf("ipv6hint=::ffff:198.51.100.100"),
            "0001076578616d706c6503636f6d000006001000000000000000000000ffffc6336464",
            DnsRecordHandlers.Svcb(1, "example.com.", listOf("ipv6hint=::ffff:c633:6464")),
        ),
        Case(1, "example.com.", listOf("key667=hello"), "0001076578616d706c6503636f6d00029b000568656c6c6f", DnsRecordHandlers.Svcb(1, "example.com.", listOf("key667=hello"))),
        Case(1, "example.com.", listOf("no-default-alpn"), null, null),
        Case(
            1, "example.com.", listOf("no-default-alpn", "alpn=h2"),
            "0001076578616d706c6503636f6d000001000302683200020000",
            DnsRecordHandlers.Svcb(1, "example.com.", listOf("alpn=h2", "no-default-alpn")),
        ),
        Case(1, "example.com.", listOf("mandatory=port"), null, null),
        Case(1, "example.com.", listOf("ech=AEP+DQA/"), "0001076578616d706c6503636f6d00000500060043fe0d003f", DnsRecordHandlers.Svcb(1, "example.com.", listOf("ech=AEP+DQA/"))),
        Case(
            null, "svc.example.com", listOf("port=8000", "port=9000", "bogus=1", "alpn="),
            "000103737663076578616d706c6503636f6d00000300021f40",
            DnsRecordHandlers.Svcb(1, "svc.example.com.", listOf("port=8000")),
        ),
    )

    @Test fun matchesLuci() {
        for (c in cases) {
            val hex = DnsRecordHandlers.buildSvcbHex(c.p, c.t, c.ps)
            assertEquals("build ${c.ps}", c.hex, hex)
            if (hex != null) assertEquals("parse ${c.ps}", c.parsed, DnsRecordHandlers.parseSvcbHex(hex))
        }
    }

    @Test fun parseEdgeCases() {
        assertNull(DnsRecordHandlers.parseSvcbHex(null))
        assertNull(DnsRecordHandlers.parseSvcbHex(""))
        assertEquals(DnsRecordHandlers.Svcb(1, "a.", emptyList()), DnsRecordHandlers.parseSvcbHex("00:01 01 61 00"))
    }
}
