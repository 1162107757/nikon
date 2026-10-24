# Histogram host tests

Run `powershell -NoProfile -File tests/histogram-java/run.ps1` from the repository root. This standalone test uses only the new pure-Java model, JDK 17 and a unique output directory under `.tooling`; it does not build the app or modify any emulator.

Fixtures cover empty versus valid black, white endpoint peaks, encoded-sRGB luminance of the primaries, a complete grey ramp, unequal RGB peak heights with a shared denominator, fixed warning predicates, percentage thresholds, immutable snapshot copies and invalid input ranges. Android panel drawing, selected controls and diagnostic-copy isolation require the integrated native verification.
