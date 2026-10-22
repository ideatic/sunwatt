package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Find inverter": subnet addresses, the probe-then-identify scan and the model name. */
class InverterFinderTest {
    @Test
    fun neighboursOfAHomeSubnet() {
        val address = assertNotNull(InverterFinder.ipv4("192.168.1.23"))
        val hosts = InverterFinder.neighbours(address, 0xFFFF_FF00L)
        assertEquals(253, hosts.size)
        assertTrue(hosts.first() == "192.168.1.1" && hosts.last() == "192.168.1.254")
        assertTrue("192.168.1.23" !in hosts && "192.168.1.0" !in hosts && "192.168.1.255" !in hosts)
    }

    @Test
    fun largeSubnetsAreNarrowedAndTinyOnesSkipped() {
        val address = assertNotNull(InverterFinder.ipv4("10.0.5.9"))
        val hosts = InverterFinder.neighbours(address, 0xFFFF_0000L)
        assertTrue(hosts.size == 253 && hosts.all { it.startsWith("10.0.5.") })
        assertTrue(InverterFinder.neighbours(address, 0xFFFF_FFFFL).isEmpty())
        assertTrue(InverterFinder.neighbours(address, 0xFFFF_FFFEL).isEmpty())
        // /30: the one other host.
        assertEquals(listOf("10.0.0.2"), InverterFinder.neighbours(assertNotNull(InverterFinder.ipv4("10.0.0.1")), 0xFFFF_FFFCL))
    }

    @Test
    fun ipv4Parsing() {
        assertEquals(0xC0A8_0132L, InverterFinder.ipv4("192.168.1.50"))
        assertNull(InverterFinder.ipv4("192.168.1"))
        assertNull(InverterFinder.ipv4("192.168.1.300"))
        assertNull(InverterFinder.ipv4("a.b.c.d"))
        assertEquals("192.168.1.50", InverterFinder.dotted(0xC0A8_0132L))
    }

    @Test
    fun candidatesOfSeveralNetworksHaveNoRepeats() {
        val a = Ipv4Network(InverterFinder.ipv4("192.168.1.23")!!, 0xFFFF_FF00L)
        val b = Ipv4Network(InverterFinder.ipv4("192.168.1.99")!!, 0xFFFF_FF00L)
        // The same /24 twice: 254 addresses (all but neither device) at most once each.
        val hosts = InverterFinder.candidatesFor(listOf(a, b))
        assertEquals(hosts.size, hosts.toSet().size)
        assertTrue("192.168.1.23" in hosts && "192.168.1.99" in hosts)
    }

    @Test
    fun findsOnlyHostsThatAnswerLikeAnInverter() = runTest {
        val registers = fakeInverterRegisters().toMutableMap()
        sunSpecText("SUN2000-15KTL-M5", 15).forEachIndexed { i, value -> registers[30000 + i] = value }
        val inverterRegisters = registers.toMap()
        val probed = ConcurrentHashMap.newKeySet<String>()
        val finder = InverterFinder(
            kind = LocalInverterKind.HUAWEI,
            candidates = { (1..254).map { "192.168.1.$it" } },
            probe = { host, port ->
                assertEquals(502, port)
                probed.add(host)
                // .7: some other device with port 502 open; .50: the dongle (unit 0); .60: dongle on unit 1.
                host in listOf("192.168.1.7", "192.168.1.50", "192.168.1.60")
            },
            makeTransport = { endpoint ->
                when (endpoint.host) {
                    "192.168.1.50" -> FakeModbusTransport(inverterRegisters, unit = 0)
                    "192.168.1.60" -> FakeModbusTransport(fakeInverterRegisters(powerW = 1000))
                    else -> FakeModbusTransport(emptyMap(), reachable = false)
                }
            },
        )
        val matches = finder.find()
        assertEquals(254, probed.size)
        assertEquals(listOf("192.168.1.50", "192.168.1.60"), matches.map { it.address })
        assertTrue(matches[0].unitID == 0 && matches[0].model == "SUN2000-15KTL-M5" && matches[0].reading.activePowerKW == 2.912)
        // No model registers: still an inverter, model unknown.
        assertTrue(matches[1].unitID == 1 && matches[1].model == null && matches[1].reading.activePowerKW == 1.0)
    }

    @Test
    fun nothingOpenFindsNothing() = runTest {
        val finder = InverterFinder(LocalInverterKind.SUN_SPEC, { listOf("192.168.1.2") }, { _, _ -> false }, { null })
        assertTrue(finder.find().isEmpty())
    }

    @Test
    fun probesRunWithinTheConcurrencyLimit() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val finder = InverterFinder(
            kind = LocalInverterKind.HUAWEI,
            candidates = { (1..200).map { "10.0.0.$it" } },
            probe = { _, _ ->
                val now = running.incrementAndGet()
                peak.accumulateAndGet(now, ::maxOf)
                kotlinx.coroutines.delay(10)
                running.decrementAndGet()
                false
            },
            makeTransport = { null },
            concurrency = 8,
        )
        assertTrue(finder.find().isEmpty())
        assertTrue(peak.get() in 2..8, "peak was ${peak.get()}")
    }

    @Test
    fun anInverterAnsweringOnBothPortsAppearsOnce() = runTest {
        val finder = InverterFinder(
            kind = LocalInverterKind.SUN_SPEC,
            candidates = { listOf("192.168.1.40") },
            probe = { _, _ -> true },
            makeTransport = { FakeModbusTransport(fakeSunSpecRegisters()) },
        )
        assertEquals(listOf("192.168.1.40"), finder.find().map { it.address })
    }

    @Test
    fun endpointsOfTheScanAreValid() {
        assertEquals("192.168.1.40", InverterFinder.hostOf("192.168.1.40:1502"))
        assertEquals(ModbusEndpoint("192.168.1.40", 1502).text, "192.168.1.40:1502")
    }
}
