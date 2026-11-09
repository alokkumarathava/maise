package com.danemadsen.maise.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun testApostropheNormalization() {
        assertEquals("don't", normalizeApostrophes("don’t"))
        assertEquals("don't", normalizeApostrophes("don‘t"))
        assertEquals("don't", normalizeApostrophes("donʼt"))
        assertEquals("I'm", normalizeApostrophes("I’m"))
    }

    @Test
    fun testContractionsPreservedUnexpanded() {
        // Contractions should retain apostrophes and NOT be expanded to "do not", "i am", etc.
        assertEquals("don't", normalizeText("don't"))
        assertEquals("don't", normalizeText("don’t"))
        assertEquals("I'm ready", normalizeText("I’m ready"))
        assertEquals("They'll arrive", normalizeText("They'll arrive"))
    }

    @Test
    fun testNonContractionsUnmodified() {
        assertEquals("cant", normalizeText("cant"))
        assertEquals("wont", normalizeText("wont"))
        assertEquals("its", normalizeText("its"))
        assertEquals("well", normalizeText("well"))
    }

    @Test
    fun testNumberAndOrdinalNormalization() {
        assertEquals("first", normalizeText("1st"))
        assertEquals("second", normalizeText("2nd"))
        assertEquals("forty two", normalizeText("42"))
        assertEquals("minus three point one four", normalizeText("-3.14"))
    }

    @Test
    fun testAbbreviationNormalization() {
        assertEquals("for example", normalizeText("e.g."))
        assertEquals("that is", normalizeText("i.e."))
        assertEquals("mister Smith", normalizeText("Mr. Smith"))
        assertEquals("doctor Jones", normalizeText("Dr. Jones"))
    }
}
