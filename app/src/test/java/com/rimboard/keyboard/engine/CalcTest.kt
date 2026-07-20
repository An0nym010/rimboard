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

    @Test
    fun `parentheses override precedence`() {
        assertEquals("= 20", chip("(2+3)*4"))
        assertEquals("= 14", chip("2+3*4"))
        assertEquals("= 3", chip("2*(1+0.5)"))
        assertEquals("= 2", chip("(8+4)/6"))
        assertEquals("= 24", chip("2*(3*(1+3))"))
    }

    @Test
    fun `unbalanced parentheses are rejected`() {
        assertNull(Calc.eval("(1+2"))
        assertNull(Calc.eval("1+2)"))
        assertNull(Calc.eval("()"))
    }

    @Test
    fun `percent of the left-hand side, the way a pocket calculator reads it`() {
        assertEquals("= 177", chip("150+18%"))   // 150 + 18% of 150
        assertEquals("= 180", chip("200-10%"))   // the classic discount
    }

    @Test
    fun `a percent operand on its own is just a hundredth`() {
        assertEquals("= 10", chip("50*20%"))
        assertEquals(0.2, Calc.eval("20%")!!, 0.0001)
    }

    @Test
    fun `a bare percentage in prose is not hijacked`() {
        // No operator, so there is nothing to calculate.
        assertNull(chip("battery at 80%"))
    }

    @Test
    fun `trailing junk is rejected rather than half-evaluated`() {
        assertNull(Calc.eval("1+2)"))
        assertNull(Calc.eval("1 2"))
    }

    @Test
    fun `converts between metric and imperial`() {
        assertEquals("= 3.1069 mi", chip("5km="))
        assertEquals("= 8.0467 km", chip("5mi="))
        assertEquals("= 22.0462 lb", chip("10kg="))
        assertEquals("= 2.54 cm", chip("1in="))
        assertEquals("= 32.8084 ft", chip("10m="))
    }

    @Test
    fun `converts temperature both ways`() {
        assertEquals("= 212 °F", chip("100c="))
        assertEquals("= 0 °C", chip("32f="))
        assertEquals("= 212 °F", chip("100°c="))
    }

    @Test
    fun `unit conversion accepts a comma decimal and a space`() {
        assertEquals("= 1.2427 mi", chip("2,0 km="))
    }

    @Test
    fun `unit conversion is case insensitive`() {
        assertEquals("= 3.1069 mi", chip("5KM="))
    }

    @Test
    fun `a unit needs the explicit equals, so prose is left alone`() {
        assertNull(chip("a 5km run"))
        assertNull(chip("5km"))
    }

    @Test
    fun `a time is not mistaken for a quantity`() {
        assertNull(chip("3pm="))
    }

    @Test
    fun `an unknown unit is ignored`() {
        assertNull(chip("5xyz="))
    }
}
