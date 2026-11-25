package app.magicpad

import app.magicpad.AutoConnectRule.MAX_WAIT_MS
import app.magicpad.AutoConnectRule.SMART_WINDOW_MS
import app.magicpad.AutoConnectRule.decide
import app.magicpad.AutoMode.DIRECT
import app.magicpad.AutoMode.SMART
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class AutoConnectRuleTest {
    private val last = "mac-a"
    private val other = "mac-b"
    private val connect = AutoDecision.Connect(last)
    private val list = AutoDecision.ShowList
    private val wait = AutoDecision.KeepWaiting

    @Test
    fun smartConnectsOnlyAfterTheWindowWhenTheLastMacIsAlone() {
        assertEquals(wait, decide(setOf(last), last, true, SMART, 0))
        assertEquals(wait, decide(setOf(last), last, true, SMART, SMART_WINDOW_MS - 1))
        assertEquals(connect, decide(setOf(last), last, true, SMART, SMART_WINDOW_MS))
    }

    @Test
    fun smartShowsTheListWhenAnotherMacIsAround() {
        assertEquals(list, decide(setOf(last, other), last, true, SMART, 0))
        assertEquals(list, decide(setOf(last, other), last, true, SMART, SMART_WINDOW_MS + 10))
        assertEquals(list, decide(setOf(other), last, true, SMART, 200)) // Last Mac missing, another here.
    }

    @Test
    fun smartWaitsUpToTheLimitWhileNothingIsFound() {
        assertEquals(wait, decide(emptySet(), last, true, SMART, SMART_WINDOW_MS))
        assertEquals(wait, decide(emptySet(), last, true, SMART, MAX_WAIT_MS - 1))
        assertEquals(list, decide(emptySet(), last, true, SMART, MAX_WAIT_MS))
        // The last Mac turning up alone after the window still connects.
        assertEquals(connect, decide(setOf(last), last, true, SMART, 3000))
    }

    @Test
    fun smartRespectsTheSettingAndNeedsALastMac() {
        assertEquals(list, decide(setOf(last), last, false, SMART, SMART_WINDOW_MS))
        assertEquals(list, decide(setOf(last), null, true, SMART, SMART_WINDOW_MS))
    }

    @Test
    fun directConnectsToTheLastMacRegardlessOfOthersOrTheSetting() {
        assertEquals(connect, decide(setOf(last, other), last, false, DIRECT, 0))
        assertEquals(wait, decide(setOf(other), last, true, DIRECT, MAX_WAIT_MS - 1))
        assertEquals(list, decide(setOf(other), last, true, DIRECT, MAX_WAIT_MS))
        assertEquals(list, decide(setOf(last), null, true, DIRECT, 0))
    }

    @Test
    fun lastUsedMacIsListedFirst() {
        val host = InetAddress.getLoopbackAddress()
        val a = MacService("Studio", "mac-x", host, 1)
        val b = MacService("Mac mini", last, host, 2)
        val c = MacService("Laptop", "mac-y", host, 3)
        assertEquals(listOf(b, a, c), AutoConnectRule.lastUsedFirst(listOf(a, b, c), last))
        assertEquals(listOf(a, b, c), AutoConnectRule.lastUsedFirst(listOf(a, b, c), null))
    }
}
