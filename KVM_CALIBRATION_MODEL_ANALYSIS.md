# KVM CALIBRATION MODEL ANALYSIS

## Data sources (63 labelled measurements)

| Dataset | n | Source | Session |
|---|---:|---|---|
| A — old 9-point documented | 9 | `KVM_CALIBRATION_REPORT.md` | one click/point, evening of 2026-09-18 (not present in the raw log; taken from the already-published table) |
| B — old 27-click raw run | 27 | `/tmp/bridgey-kvm-cal.log`, 2026-09-18 21:48–21:49 (3 runs × 9 points) | reconstructed from raw `down` events; verified to reproduce `KVM_CALIBRATION_PRECISION_REPORT.md` Runs 1–3 exactly (e.g. Run1/Point1 → 84.72, 82.01 matches to 2 decimals) |
| C — current 27-click run | 27 | `/tmp/bridgey-kvm-cal.log`, 2026-09-19 06:27–06:28 (3 runs × 9 points) | not previously tabulated anywhere; extracted here directly from the raw log's `down` events |

A 4th cluster in the log (2026-09-20 14:09, 4 clicks near screen-center) was excluded — it isn't a 9-point raster pass, it's an unrelated drag/move test.

Each click's normalized Mac coordinate `(nx, ny)` was mapped through the **current, unmodified transform** — `androidX = nx * 1440`, `androidY = ny * 3088` — and compared against the known expected raster-marker center for that point (same 9-point target table used in both prior reports). Point identity within each 9-click run was recovered by ordering (TL, TC, TR, ML, MR, MC, BL, BC, BR), independently confirmed by the fact that dataset B reproduces the already-published precision-report values exactly.

## Current transform

```
androidX = normalizedX * 1440
androidY = normalizedY * 3088
```

## Baseline (no calibration)

| Dataset | MAE X/Y (px) | RMS X/Y (px) | Max abs X/Y (px) | Mean Euclidean (px) | Max Euclidean (px) |
|---|---|---|---|---|---|
| A (old 9pt) | 6.78 / 3.94 | 8.21 / 4.68 | 15.18 / 8.32 | 8.61 | 15.53 |
| B (old 27) | 4.12 / 5.16 | 4.98 / 7.83 | 10.38 / 29.64 | 7.41 | 29.69 |
| C (current 27) | 7.88 / 6.92 | 9.00 / 8.68 | 16.26 / 20.10 | 11.51 | 20.76 |
| All 63 | 6.11 / 5.74 | 7.41 / 7.86 | 16.26 / 29.64 | 9.34 | 29.69 |

Note the baseline error is *not* stable across independently-run sessions (7.4px vs 11.5px mean Euclidean) — a first sign that some of the residual is session-level noise, not a fixed geometric defect.

## Candidate models

### 1. Translation — `X' = X + dx`, `Y' = Y + dy`
### 2. Independent affine — `X' = ax·X + bx`, `Y' = ay·Y + by`
### 3. Full affine — `X' = a·X + b·Y + c`, `Y' = d·X + e·Y + f`
### 4. Quadratic (exploratory only, not adopted — see below)

All three linear models were fit by ordinary least squares against the known target pixel positions, operating on the **current transform's pixel output** (`X = nx·1440`, `Y = ny·3088`) as input.

## Leave-one-session-out cross-validation

This is the decisive test: does a transform learned from two sessions predict the third, independently-collected session?

| Fold | Model | Fitted params | Train mean-Eucl | **Test mean-Eucl (out-of-sample)** | Baseline test mean-Eucl | Δ (improvement) |
|---|---|---|---:|---:|---:|---:|
| A: train(9+old27) → test(current27) | translation | dx=−3.12, dy=−4.39 | 6.28 | **10.15** | 11.51 | **+1.36** |
| | independent affine | ax=0.997, bx=−1.14, ay=0.998, by=−0.96 | 5.90 | 9.23 | 11.51 | +2.28 |
| | full affine | a=0.997, b≈0, c=−0.09, d≈0, e=0.998, f=−2.47 | 5.71 | 9.22 | 11.51 | +2.29 |
| B: train(9+current27) → test(old27) | translation | dx=−4.01, dy=−3.20 | 9.41 | **5.96** | 7.41 | **+1.45** |
| | independent affine | ax=0.989, bx=**+3.60**, ay=0.998, by=0.30 | 6.91 | 7.87 | 7.41 | **−0.46 (worse)** |
| | full affine | a=0.989, b≈0, c=**+4.82**, d≈0, e=0.998, f=0.05 | 6.91 | 7.78 | 7.41 | **−0.37 (worse)** |
| C: train(old27+current27) → test(9pt) | translation | dx=−3.49, dy=−3.84 | 8.01 | **7.26** | 8.61 | **+1.35** |
| | independent affine | ax=0.996, bx=−0.28, ay=0.997, by=0.13 | 7.44 | 5.76 | 8.61 | +2.86 |
| | full affine | a=0.996, b≈0, c=1.16, d≈0.001, e=0.997, f=−0.60 | 7.33 | 5.70 | 8.61 | +2.91 |

### Reading this

- **Translation improves out-of-sample accuracy in all three independent folds**, by a consistent ~1.3–1.5 px of mean Euclidean error (≈15–18% reduction), and the fitted offset itself is stable across folds: `dx ∈ [−4.01, −3.12]`, `dy ∈ [−4.39, −3.20]` — always negative, always the same order of magnitude.
- **Affine (independent or full) is *not* stable.** It wins two of three folds by a larger margin, but **actively makes fold B worse than doing nothing** (mean Euclidean 7.87–7.78 vs. 7.41 baseline). Inspecting *why*: the fitted intercept term swings from −1.14 to **+3.60** to −0.28 across folds — sign-flipping — while the fitted scale stays put at 0.989–0.997 (i.e. within 1–1.5% of 1.0, never showing a real scale defect). That intercept instability is the signature of a model fitting fold-specific noise, not a real geometric relationship — precisely the overfitting failure mode the cross-validation was designed to catch.
- The two datasets' baseline bias directions agree (A: +3.79/+3.64, B: +2.90/+4.64, C: +4.09/+3.05 — all positive, same order of magnitude across three independently-run sessions), which is the signature of a real, reproducible small offset rather than session noise.

**Conclusion: a constant translation is justified by consistent out-of-sample improvement across independent sessions. A scale or full-affine correction is not — its fitted scale is statistically indistinguishable from 1.0, and its fitted offset does not generalize.**

## Full-dataset fit (all 63) — final parameters, reported for completeness, not as evidence of generalization

| Model | Params | Train mean-Eucl |
|---|---|---:|
| translation | dx = −3.5335, dy = −3.8140 | 7.90 |
| independent affine | ax=0.9943, bx=+0.595, ay=0.9976, by=−0.134 | 7.13 |
| full affine | a=0.9943, b=−0.0008, c=1.863, d=0.0011, e=0.9976, f=−0.948 | 7.05 |
| quadratic (exploratory) | 6 coeffs/axis, train mean-Eucl 6.69 | — not adopted, see below |

The quadratic fit was evaluated purely to check "is there a nonlinear signature." Its per-axis coefficients (`x²`, `y²`, `xy` terms) are all on the order of 1e-6 — negligible relative to the linear terms (~1.0) — meaning the apparent extra training-set improvement (6.69 vs 7.05 px) is fitting per-point noise with 6 parameters against as few as 9 training points in some folds, not a real curvature. It is not cross-validated here (insufficient degrees of freedom on the 9-point fold) and is explicitly rejected as overfitting.

## Spatial residual analysis (baseline, all 63, pooled)

| Group | Mean error X/Y (px) | Mean Euclidean (px) |
|---|---|---:|
| Left column (pts 1,4,7) | −0.50 / +3.77 | 9.19 |
| Center column (pts 2,6,8) | +4.41 / +5.34 | 9.17 |
| Right column (pts 3,5,9) | +6.69 / +2.34 | 9.65 |
| Top row (pts 1,2,3) | +0.71 / +1.70 | 8.30 |
| Middle row (pts 4,5,6) | +6.67 / +1.16 | 9.17 |
| Bottom row (pts 7,8,9) | +3.22 / +8.59 | 10.55 |

The previously-hypothesized pattern ("left-column X negative, right-column X positive" / "bottom-row Y positive") shows up **only weakly and inconsistently** once split by session:

- Left-column X error: A = −4.48, B = +2.89, C = −2.57 (sign flips between sessions)
- Right-column X error: A = +12.64, B = +1.72, C = +9.69 (all positive, but ranges over a 7× spread — not a stable per-column constant)
- Bottom-row Y error: A = +4.68, B = +9.73, C = +8.76 (consistently positive, but this is dominated by the single 29.6px point-8 outlier in dataset B — without it, bottom-row Y is much closer to the other rows, as the precision report already noted)

This is consistent with the cross-validation finding: there's a real small *overall* bias (→ translation), but no stable *directional/positional* gradient across the screen that would justify a scale or affine term. The "left negative / right positive" impression comes mostly from dataset A's small n=3-per-column sample size, not a reproducible spatial trend.

## Practical conclusion

**TRANSLATION.**

- `dx = −3.5335 px`, `dy = −3.8140 px` (fit on all 63 samples; consistent with every leave-one-out fold's fitted value)
- Justified by stable, positive out-of-sample improvement in all three independent cross-validation folds (~1.3–1.5 px mean-Euclidean reduction, ~15–18%)
- Scale/full-affine explicitly rejected: fitted scale ≈ 1.0 (no real defect) and fitted offset term is unstable/sign-flipping across folds, actively regressing one fold below baseline
- Quadratic/nonlinear explicitly rejected: coefficients are noise-scale, not cross-validated, insufficient data to support 6 free parameters

## Before / after (chosen translation applied to each dataset)

| Dataset | MAE X/Y before | MAE X/Y after | Mean Eucl before | Mean Eucl after |
|---|---|---|---:|---:|
| A (old 9pt) | 6.78 / 3.94 | 6.56 / 2.45 | 8.61 | 7.26 |
| B (old 27) | 4.12 / 5.16 | 3.21 / 3.98 | 7.41 | 5.91 |
| C (current 27) | 7.88 / 6.92 | 6.75 / 6.10 | 11.51 | 10.11 |
| All 63 | 6.11 / 5.74 | 5.20 / 4.67 | 9.34 | 7.90 |

The improvement is real but modest — this correction removes the *average* bias, not the *variance* (click/measurement noise, e.g. the isolated 29.6px point-8 outlier, dominates the max-error figures both before and after and is unaffected by a constant offset). It carries no downside risk since it is a pure constant shift with no scale/rotation component that could distort geometry elsewhere on the screen.

## Implementation

Applied as the smallest possible isolated change, **without touching the frozen KVM foundation** (`KvmCoordinateMapper.kt` untouched):

- New file `macos/Sources/BridgeyMac/KvmPointerCalibration.swift` — a small, pure, unit-tested function expressing the offset as a fraction of the normalized (0…1) coordinate space (`−3.5335/1440`, `−3.8140/3088`), so it stays proportionally correct if a different Android display resolution connects, matching the wire protocol's own already-normalized design.
- One-line change in `macos/Sources/BridgeyMac/ScreenShareWindow.swift`'s `KvmMouseCaptureView.report(_:_:)` — the single call site that turns a captured Mac mouse point into the normalized value handed to the frozen input transport — applying the calibration after `VideoContentGeometry.normalizedPoint` returns.
- `VideoContentGeometry.swift` itself is untouched: it is a generic, reusable geometry helper (also covered by its own generic tests with arbitrary source/bounds sizes) and the calibration offset is specific to this input path, not to geometry in general.
