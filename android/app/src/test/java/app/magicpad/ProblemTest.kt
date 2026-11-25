package app.magicpad

import app.magicpad.net.FailKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemTest {
    @Test
    fun everyFailureNamesTheMacAndSaysWhatToDo() {
        for (kind in FailKind.entries) {
            val p = Problem.of(kind, "Mac mini")
            assertTrue("$kind title", p.title.contains("Mac mini"))
            assertTrue("$kind help", p.help.isNotBlank())
            for (raw in listOf("Exception", "errno", "reset by peer", "null")) assertFalse("$kind leaks \"$raw\"", p.help.contains(raw))
        }
    }

    @Test
    fun onlyAVersionMismatchCantBeFixedByTryingAgain() {
        for (kind in FailKind.entries) {
            assertTrue(kind.name, Problem.of(kind, "Mac").canRetry == (kind != FailKind.INCOMPATIBLE))
        }
        assertTrue(Problem.denied("Mac").canRetry)
    }

    @Test
    fun aPairingTimeoutSaysToClickAllow() {
        assertTrue(Problem.of(FailKind.PAIRING_TIMEOUT, "Mac").help.contains("Allow"))
    }
}
