package com.niftyoi.dhan

import com.niftyoi.dhan.domain.calculations.Interpretation
import com.niftyoi.dhan.domain.models.Signal
import org.junit.Assert.*
import org.junit.Test

class InterpretationTest {
    @Test fun fourQuadrants() {
        assertEquals(Signal.LONG_BUILDUP, Interpretation.classify(1.0, 5))
        assertEquals(Signal.SHORT_BUILDUP, Interpretation.classify(-1.0, 5))
        assertEquals(Signal.SHORT_COVERING, Interpretation.classify(1.0, -5))
        assertEquals(Signal.LONG_UNWINDING, Interpretation.classify(-1.0, -5))
        assertEquals(Signal.NEUTRAL, Interpretation.classify(0.0, 5))
    }

    @Test fun optionTextIsContractSpecificAndHedged() {
        val t = Interpretation.optionContext(-10.0, 5.0, 100, true)
        assertTrue(t.contains("CE"))
        assertTrue(t.contains("not a prediction"))
        assertFalse(t.lowercase().contains("bearish"))
    }
}
