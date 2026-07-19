package com.rimboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcTest {

    private fun chip(s: String) = Calc.chipFor(s, 40)

    @Test
    fun `evaluates the basic operators`() {
        assertEquals("= 408", chip("12*34"))
        assertEquals("= 17", chip("12+5"))
        assertEquals("= 25", chip("100/4"))
        assertEquals("= 42", chip("7×6"))
        assertEquals("= 9", chip("81÷9"))
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals("= 14", chip("2+3*4"))
        assertEquals("= 15", chip("1+2+3+4+5"))
    }

    @Test
    fun `handles decimals in both notations`() {
        assertEquals("= 3.3333", chip("10/3"))
        assertEquals("= 4", chip("1,5+2,5"))
        assertEquals("= 7", chip("3.5*2"))
    }

    @Test
    fun `reads an expression at the end of a sentence`() {
        assertEquals("= 408", chip("total 12*34"))
    }

    @Test
    fun `leaves dates and phone numbers alone`() {
        assertNull(chip("meet at 12/07/2026"))
        assertNull(chip("call 555-1234"))
    }

    @Test
    fun `an explicit equals overrides the date and phone guards`() {
        assertEquals("= -679", chip("555-1234="))
    }

    @Test
    fun `ignores things that are not arithmetic`() {
        assertNull(chip("hello world"))
        assertNull(chip("5"))
        assertNull(chip("2026"))
    }

    @Test
    fun `refuses to divide by zero`() {
        assertNull(chip("1/0"))
    }

    @Test
    fun `refuses an expression that fills the whole read window`() {
        // Could be truncated mid-number, so the result would be wrong.
        val full = "1234567890+1234567890+1234567890+123456"
        assertNull(Calc.chipFor(full, full.length))
    }

    @Test
    fun `rejects absurdly large results rather than showing junk`() {
        assertNull(Calc.format(1e13))
        assertEquals("0", Calc.format(-0.00001))
    }

    @Test
    fun `eval rejects malformed input`() {
        assertNull(Calc.eval("*5"))
        assertNull(Calc.eval("abc"))
        assertNull(Calc.eval("1+"))
        assertNull(Calc.eval("1 2"))
    }

    @Test
    fun `an operand may carry its own sign`() {
        // This is what lets "-5+3" work, so "1++2" reads as 1 + (+2) = 3
        // rather than being rejected.
        assertEquals(-2.0, Calc.eval("-5+3")!!, 0.0001)
        assertEquals(3.0, Calc.eval("1++2")!!, 0.0001)
    }
}
