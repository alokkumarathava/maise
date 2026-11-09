package com.danemadsen.maise.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SynthesisOrchestratorTest {

    @Test
    fun testCancellationFlag() {
        val flag = AtomicBoolean(false)
        assertFalse(flag.get())
        flag.set(true)
        assertTrue(flag.get())
    }
}
