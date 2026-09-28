# Bridgey KVM Calibration Precision Report

## Test Setup

```text
Device: Samsung Galaxy S23 Ultra
Android display: 1440 x 3088
Density: 3.5
Density DPI: 560
Rotation: 0
Raster: 852 x 1846 RGB PNG
Raster display view: 1440 x 3088
macOS capture view: 480 x 900
Video source: 720 x 1544
contentRect: x=30.1554, y=0, width=419.6891, height=900
Samples: 3 complete runs x 9 markers = 27 clicks
```

The user clicked the center of each colored marker in every run.

## Expected Android Marker Centers

| Point | Expected X | Expected Y |
|---:|---:|---:|
| 1 | 79.44 | 77.79 |
| 2 | 719.15 | 77.79 |
| 3 | 1358.87 | 77.79 |
| 4 | 78.59 | 1495.49 |
| 5 | 1358.03 | 1495.49 |
| 6 | 720.00 | 1495.49 |
| 7 | 79.44 | 2970.90 |
| 8 | 719.15 | 2971.74 |
| 9 | 1358.87 | 2970.90 |

## Raw Samples

Values are Android mapped pixels and error from the expected rendered marker center.

| Run | Point | Actual X | Actual Y | Error X | Error Y |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 84.72 | 82.01 | +5.28 | +4.22 |
| 1 | 2 | 719.88 | 78.82 | +0.73 | +1.03 |
| 1 | 3 | 1362.88 | 75.86 | +4.01 | -1.93 |
| 1 | 4 | 86.61 | 1496.50 | +8.02 | +1.01 |
| 1 | 5 | 1361.79 | 1494.49 | +3.76 | -1.00 |
| 1 | 6 | 726.10 | 1493.83 | +6.10 | -1.66 |
| 1 | 7 | 83.11 | 2984.83 | +3.67 | +13.93 |
| 1 | 8 | 717.41 | 3001.38 | -1.74 | +29.64 |
| 1 | 9 | 1359.18 | 2976.69 | +0.31 | +5.79 |
| 2 | 1 | 78.50 | 76.70 | -0.94 | -1.09 |
| 2 | 2 | 723.12 | 78.55 | +3.97 | +0.76 |
| 2 | 3 | 1353.63 | 80.89 | -5.24 | +3.10 |
| 2 | 4 | 88.97 | 1498.90 | +10.38 | +3.41 |
| 2 | 5 | 1361.79 | 1498.59 | +3.76 | +3.10 |
| 2 | 6 | 728.32 | 1494.15 | +8.32 | -1.34 |
| 2 | 7 | 87.16 | 2984.14 | +7.72 | +13.24 |
| 2 | 8 | 724.10 | 2977.99 | +4.95 | +6.25 |
| 2 | 9 | 1359.77 | 2973.74 | +0.90 | +2.84 |
| 3 | 1 | 73.37 | 84.53 | -6.07 | +6.74 |
| 3 | 2 | 721.22 | 83.50 | +2.07 | +5.71 |
| 3 | 3 | 1358.78 | 80.35 | -0.09 | +2.56 |
| 3 | 4 | 79.05 | 1500.33 | +0.46 | +4.84 |
| 3 | 5 | 1360.65 | 1496.37 | +2.62 | +0.88 |
| 3 | 6 | 723.70 | 1502.79 | +3.70 | +7.30 |
| 3 | 7 | 76.93 | 2973.84 | -2.51 | +2.94 |
| 3 | 8 | 727.75 | 2975.85 | +8.60 | +4.11 |
| 3 | 9 | 1364.28 | 2979.71 | +5.41 | +8.81 |

## Per-Point Statistics

Population statistics are based on the three samples for each point. `stddev` uses sample standard deviation.

| Point | Mean X error | Mean Y error | Stddev X | Stddev Y | Min/Max X | Min/Max Y |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | -0.58 | +3.29 | 5.68 | 4.00 | -6.07/+5.28 | -1.09/+6.74 |
| 2 | +2.26 | +2.50 | 1.63 | 2.78 | +0.73/+3.97 | +0.76/+5.71 |
| 3 | -0.44 | +1.24 | 4.63 | 2.76 | -5.24/+4.01 | -1.93/+3.10 |
| 4 | +6.29 | +3.09 | 5.18 | 1.94 | +0.46/+10.38 | +1.01/+4.84 |
| 5 | +3.38 | +0.99 | 0.66 | 2.05 | +2.62/+3.76 | -1.00/+3.10 |
| 6 | +6.04 | +1.43 | 2.31 | 5.08 | +3.70/+8.32 | -1.66/+7.30 |
| 7 | +2.96 | +10.03 | 5.15 | 6.16 | -2.51/+7.72 | +2.94/+13.93 |
| 8 | +3.94 | +13.33 | 5.24 | 14.16 | -1.74/+8.60 | +4.11/+29.64 |
| 9 | +2.21 | +5.81 | 2.79 | 2.98 | +0.31/+5.41 | +2.84/+8.81 |

## Spatial Error Analysis

### Constant offset

No single constant offset explains all points:

- Point 1 mean X error: `-0.58 px`
- Point 4 mean X error: `+6.29 px`
- Point 7 mean X error: `+2.96 px`

The Y error also varies substantially by row.

### Horizontal scale error

There is a positive X bias in the middle row, especially points 4 and 6, but the right edge does not monotonically increase:

```text
Top row:    -0.58, +2.26, -0.44
Middle row: +6.29, +3.38, +6.04
Bottom row: +2.96, +3.94, +2.21
```

This is not sufficient evidence for a pure left-to-right scale error.

### Vertical scale error

Bottom-row means are higher than top-row means, but point 8 has a large first-run outlier of `+29.64 px`. Without that outlier, the bottom row is much closer to the other rows. The data does not establish a clean linear Y scale error.

### Random or measurement error

The repeated measurements show real variance even though the user clicked marker centers. The largest variance is point 8 vertically:

```text
Point 8 Y stddev: 14.16 px
```

That point should be treated as an outlier or as evidence that the assumed rendered marker center for the bottom-center marker needs another independent check.

## Conclusion

The repeated test does not justify changing the KVM geometry, Android mapper, protocol, or frozen foundation.

The result is best classified as:

```text
Geometry appears broadly correct, but a small residual pattern remains unresolved.
```

There is no demonstrated constant `+32 px` offset. There is also no clean monotonic horizontal or vertical scale signature across all nine points.

The next useful measurement would be to independently verify the physical rendered center of marker 8 and repeat only the bottom-center point with a more controlled positioning method. No correction should be implemented before that check.

No application architecture, coordinate calculation, Android mapper, protocol, or frozen KVM foundation was changed. No commit was created.
