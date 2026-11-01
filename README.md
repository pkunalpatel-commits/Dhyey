# NIFTY OI PROFILE – DHAN

Android app (Kotlin, Jetpack Compose, MVVM, Room, OkHttp, Lightweight Charts in a WebView) for NIFTY futures/options OI analytics on DhanHQ v2 data.

## Honest status
Written without access to an Android SDK or Dhan credentials. What was actually verified before packaging:

| Verified here | How |
|---|---|
| All calculation code (bins, POC, VAH/VAL, PCR, OI change, high-OI ranking, premium profile, heatmap, developing POC, candle resampling, VWAP, interpretation, chart-level builder, JSON, binary feed parser) | compiled with kotlinc 1.9.24; **44 unit tests pass** |
| Chart page logic (incremental tick updates, no reload per tick, replay, strike vs premium level separation) | Node test against a mocked chart library (`app/src/test/chart-logic.test.js`) |
| Lightweight Charts v4.2.0 bundled locally | from npm, Apache-2.0, NOTICE + attribution link included |

**NOT verified:** Android/Compose/Room/OkHttp code has never been compiled; nothing has run against live Dhan. Expect a few compile errors on the first CI run, and expect Dhan field-name mismatches to need adjusting. Please test with real credentials before trusting any number.

## Build the APK
Push to GitHub → Actions → "Build debug APK" → download artifact `app-debug-apk` (`app/build/outputs/apk/debug/app-debug.apk`). The workflow runs the JS test, Kotlin unit tests, then `assembleDebug`, and uploads reports on failure. Send me the log if it fails.

## Use
Settings → Client ID + Access Token (Dhan web → Profile → DhanHQ Trading APIs; Data APIs subscription needed for option chain, quotes, WebSocket and intraday candles). Dashboard → Refresh, or turn Auto on.

## Features (all untested live)
Dashboard · Futures OI profile · Options OI (strike CE/PE/Combined, premium, OI change with 1/3/5/15/30-min windows from stored snapshots) · Option chain with IV/Greeks/volume · Individual CE/PE chart (3 synced panels: underlying + strike OI levels, option premium + VWAP/volume + premium OI levels, OI + ΔOI; OI reference strip; freeze/compare levels; replay; live ticks over Dhan WebSocket with auto-reconnect) · High-OI scanner (top 3/5/10) · OI heatmap · Snapshot history replay + developing POC · Settings.

## Method notes
- Futures and premium profiles are **snapshot-observation-weighted** (each stored observation adds its OI to its bin). They are not exchange OI at each traded price and not unique contracts opened per price.
- Dhan's option chain `previous_oi` is previous-close OI. "Prev close" ΔOI uses it; minute windows compare with snapshots this app saved earlier (so they need history).
- Strike levels are drawn only on the underlying panel; premium levels only on the option-premium panel.
- Signals are analytical indications, not predictions.

## Known gaps vs the original spec
- Derived profiles (futures/strike/premium/change/high-OI) are computed on the fly from stored snapshots rather than saved in their own Room tables; settings live in SharedPreferences, not a table.
- Chart-rendering and WebSocket reconnection have no automated Android tests (JS logic test only; reconnect logic is untested).
- Dhan intraday history is only available for recent days; no data is fabricated if absent. 3m and 30m candles are built from 1m candles.
- Candle timestamps are normalised heuristically (handles both Unix and 1980-based epochs); live candles use the device clock.
- Release signing is not configured: create a keystore, store it and passwords as GitHub Secrets, add a `signingConfig` and an `assembleRelease` step.
- Lightweight Charts v4 attribution: link shown on the chart page; see `assets/chart/NOTICE-LIGHTWEIGHT-CHARTS.txt`.
