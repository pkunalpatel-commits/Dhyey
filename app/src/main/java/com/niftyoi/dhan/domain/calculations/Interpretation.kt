package com.niftyoi.dhan.domain.calculations

import com.niftyoi.dhan.domain.models.Signal

/** Analytical indications only. They are not predictions or trade advice. */
object Interpretation {
    fun classify(priceChange: Double, oiChange: Long): Signal = when {
        priceChange > 0 && oiChange > 0 -> Signal.LONG_BUILDUP
        priceChange < 0 && oiChange > 0 -> Signal.SHORT_BUILDUP
        priceChange > 0 && oiChange < 0 -> Signal.SHORT_COVERING
        priceChange < 0 && oiChange < 0 -> Signal.LONG_UNWINDING
        else -> Signal.NEUTRAL
    }

    fun label(s: Signal): String = when (s) {
        Signal.LONG_BUILDUP -> "Long buildup indication"
        Signal.SHORT_BUILDUP -> "Short buildup indication"
        Signal.SHORT_COVERING -> "Short covering indication"
        Signal.LONG_UNWINDING -> "Long unwinding indication"
        Signal.NEUTRAL -> "No clear indication"
    }

    /**
     * Contract-specific reading for one option. The signal is based on THIS contract's premium and OI;
     * the underlying move is only added as context. A CE OI increase is not automatically bearish
     * and a PE OI increase is not automatically bullish.
     */
    fun optionContext(underlyingChange: Double, premiumChange: Double, oiChange: Long, isCall: Boolean): String {
        val s = classify(premiumChange, oiChange)
        val kind = if (isCall) "CE" else "PE"
        val base = when (s) {
            Signal.LONG_BUILDUP -> "Premium and OI both rising: fresh positions being added in this $kind that lift price (typically buyer-led)."
            Signal.SHORT_BUILDUP -> "Premium falling while OI rises: fresh positions being added into a falling premium (typically writer-led)."
            Signal.SHORT_COVERING -> "Premium rising while OI falls: positions being closed into a rising premium (typically short covering)."
            Signal.LONG_UNWINDING -> "Premium and OI both falling: existing positions being closed (typically long unwinding)."
            Signal.NEUTRAL -> "No clear premium/OI direction."
        }
        val ctx = when {
            underlyingChange > 0 -> "Underlying is up in the same window."
            underlyingChange < 0 -> "Underlying is down in the same window."
            else -> "Underlying is flat in the same window."
        }
        return "$base $ctx Indication only, not a prediction."
    }
}
