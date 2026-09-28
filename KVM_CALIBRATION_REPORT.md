# Bridgey KVM Calibration Report

## Raster

```text
File: raster.png
Dimensions: 852 x 1846 px
Format: RGB PNG
Displayed target coordinate system: 1440 x 3120
```

Detected marker centers in raster pixels:

```text
1: (47.0, 46.5)
2: (425.5, 46.5)
3: (804.0, 46.5)

4: (46.5, 894.0)
5: (803.5, 894.0)
6: (426.0, 894.0)

7: (47.0, 1776.0)
8: (425.5, 1776.5)
9: (804.0, 1776.0)
```

The outer markers are partially clipped by the raster edges, so their center estimates have approximately 1-2 px uncertainty.

## Android Display

```text
Display: 1440 x 3088
Density: 3.5
Density DPI: 560
Rotation: 0
Insets: top 125, bottom 53
```

The raster is rendered into the actual Android view:

```text
View: 1440 x 3088
Scale X: 1440 / 852  = 1.690140845
Scale Y: 3088 / 1846 = 1.672806067
```

Expected rendered marker centers:

| Point | Expected Android marker center |
|---:|---:|
| 1 | `(79.44, 77.79)` |
| 2 | `(719.15, 77.79)` |
| 3 | `(1358.87, 77.79)` |
| 4 | `(78.59, 1495.49)` |
| 5 | `(1358.03, 1495.49)` |
| 6 | `(720.00, 1495.49)` |
| 7 | `(79.44, 2970.90)` |
| 8 | `(719.15, 2971.74)` |
| 9 | `(1358.87, 2970.90)` |

## macOS Geometry

```text
Capture view bounds: 480 x 900
Video source: 720 x 1544
```

Current content rectangle:

```text
x      = 30.1554
y      = 0
width  = 419.6891
height = 900
```

The portrait video is letterboxed horizontally.

## Measured Pipeline

| Point | macOS normalized | Video/source coordinate | Android mapped pixel | Error vs rendered marker |
|---:|---:|---:|---:|---:|
| 1 | `(0.052021, 0.025004)` | `(37.46, 38.61)` | `(74.91, 77.21)` | `(-4.53, -0.57)` |
| 2 | `(0.499572, 0.026576)` | `(359.69, 41.03)` | `(719.38, 82.07)` | `(+0.23, +4.28)` |
| 3 | `(0.952251, 0.024931)` | `(685.62, 38.49)` | `(1371.24, 76.99)` | `(+12.37, -0.80)` |
| 4 | `(0.050858, 0.486233)` | `(36.62, 750.74)` | `(73.24, 1501.49)` | `(-5.36, +6.00)` |
| 5 | `(0.953619, 0.485352)` | `(686.61, 749.38)` | `(1373.21, 1498.77)` | `(+15.18, +3.28)` |
| 6 | `(0.504793, 0.486398)` | `(363.45, 751.00)` | `(726.90, 1502.00)` | `(+6.90, +6.51)` |
| 7 | `(0.052692, 0.964774)` | `(37.94, 1489.61)` | `(75.88, 2979.22)` | `(-3.56, +8.32)` |
| 8 | `(0.501163, 0.962843)` | `(360.84, 1486.63)` | `(721.68, 2973.26)` | `(+2.52, +1.52)` |
| 9 | `(0.950864, 0.963438)` | `(684.62, 1487.55)` | `(1369.24, 2975.10)` | `(+10.37, +4.19)` |

## Numerical Transformation

```text
Raster pixel
    -> Android raster view

xAndroid = xRaster * 1.690140845
yAndroid = yRaster * 1.672806067
```

macOS maps a mouse point through the content rectangle:

```text
normalizedX = (macX - 30.1554) / 419.6891
normalizedY = macY / 900
```

The normalized values are then interpreted in the video source space:

```text
xSource = normalizedX * 720
ySource = normalizedY * 1544
```

Android maps the same normalized values using its display metrics:

```text
xAndroid = normalizedX * 1440
yAndroid = normalizedY * 3088
```

## Interpretation

Verified:

- Input transport is correct.
- Normalized values arrive intact.
- Android mapper math is correct.
- macOS `contentRect` is internally consistent.
- No coordinate-axis inversion is present.
- No 32 px Android inset offset is present.
- No obvious vertical scale failure exists in the measured pipeline.

The raster design height is 3120 px while the actual Android display height is 3088 px:

```text
3120 - 3088 = 32 px
```

The raster is stretched directly into the actual `1440 x 3088` Android view. The system insets are reported as `125/53`, but the calibration view uses the full `1440 x 3088` bounds, so the insets are not applied as an additional coordinate offset.

Vertical errors were approximately:

```text
Top:    -0.8 to +4.3 px
Center: +3.3 to +6.5 px
Bottom: +1.5 to +8.3 px
```

This does not show a 32 px translation or a severe scale defect. The remaining few-pixel differences are consistent with manually clicking the visual marker center and uncertainty in the clipped edge-marker centers.

## Conclusion

The current evidence does not justify adding calibration constants or changing the coordinate calculations.

The transport, normalization, and Android mapper are behaving as expected. The 3120-to-3088 difference belongs to the raster design size versus the actual phone display size and is not currently evidence of a mapper defect.

No coordinate calculations, Android mapper, frozen KVM foundation, or protocol were modified during this experiment. No commit was created.
