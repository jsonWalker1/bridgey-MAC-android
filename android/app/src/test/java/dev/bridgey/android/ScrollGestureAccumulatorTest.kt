package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** KVM MOUSE V2 PHASE 2 (see KVM_MOUSE_V2_PHASE1.md scroll injection report): coverage for the pure
 * coalescing logic that turns a burst of ~80-90 SCROLL wheel events/s into at most one Accessibility
 * gesture request per coalescing window. */
class ScrollGestureAccumulatorTest {
    @Test fun singleScrollEventProducesOneRequestWithThatDelta() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 10f, x = 100f, y = 200f)
        val request = accumulator.drain()
        assertEquals(ScrollGestureRequest(x = 100f, y = 200f, dx = 0f, dy = 10f), request)
    }

    @Test fun fastBurstOfEventsCoalescesIntoOnlyOneRequest() {
        val accumulator = ScrollGestureAccumulator()
        // Simulate ~90 events/s worth of ticks arriving inside one coalescing window.
        repeat(90) { accumulator.accumulate(dx = 0f, dy = 1f, x = 100f, y = 200f) }
        val first = accumulator.drain()
        val second = accumulator.drain()
        assertEquals(90f, first?.dy)
        assertNull("draining twice without a new accumulate must not produce a second gesture", second)
    }

    @Test fun coalescingSumsMultipleDeltaValues() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 1f, dy = 2f, x = 0f, y = 0f)
        accumulator.accumulate(dx = 3f, dy = -5f, x = 0f, y = 0f)
        accumulator.accumulate(dx = -0.5f, dy = 1.5f, x = 0f, y = 0f)
        val request = accumulator.drain()
        assertEquals(3.5f, request?.dx)
        assertEquals(-1.5f, request?.dy)
    }

    @Test fun positiveDeltaIsPreservedExactly() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 42f, x = 0f, y = 0f)
        assertEquals(42f, accumulator.drain()?.dy)
    }

    @Test fun negativeDeltaIsPreservedExactly() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = -42f, x = 0f, y = 0f)
        assertEquals(-42f, accumulator.drain()?.dy)
    }

    @Test fun zeroDeltaProducesNoRequest() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 0f, x = 100f, y = 100f)
        assertNull(accumulator.drain())
    }

    @Test fun equalAndOppositeDeltasCoalesceToZeroAndProduceNoRequest() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 20f, x = 0f, y = 0f)
        accumulator.accumulate(dx = 0f, dy = -20f, x = 0f, y = 0f)
        assertNull(accumulator.drain())
    }

    @Test fun extremeDeltaIsClampedToTheConfiguredMaximum() {
        val accumulator = ScrollGestureAccumulator(maxDistancePx = 600f)
        accumulator.accumulate(dx = 0f, dy = 100_000f, x = 0f, y = 0f)
        assertEquals(600f, accumulator.drain()?.dy)
    }

    @Test fun extremeNegativeDeltaIsClampedToTheConfiguredMinimum() {
        val accumulator = ScrollGestureAccumulator(maxDistancePx = 600f)
        accumulator.accumulate(dx = 0f, dy = -100_000f, x = 0f, y = 0f)
        assertEquals(-600f, accumulator.drain()?.dy)
    }

    @Test fun draimingWithNothingPendingProducesNoRequest() {
        val accumulator = ScrollGestureAccumulator()
        assertNull(accumulator.drain())
    }

    @Test fun repeatedDrainsWithoutNewAccumulationNeverProduceMoreThanOneRequest() {
        // Regression guard for "no infinite gesture pile-up": once drained, the accumulator must stay
        // empty until something new is accumulated, no matter how many times drain() is called.
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 5f, x = 0f, y = 0f)
        assertEquals(5f, accumulator.drain()?.dy)
        repeat(10) { assertNull(accumulator.drain()) }
    }

    @Test fun requestOriginatesAtTheMostRecentPointerPosition() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 1f, x = 10f, y = 20f)
        accumulator.accumulate(dx = 0f, dy = 1f, x = 30f, y = 40f)
        accumulator.accumulate(dx = 0f, dy = 1f, x = 50f, y = 60f)
        val request = accumulator.drain()
        assertEquals(50f, request?.x)
        assertEquals(60f, request?.y)
    }

    @Test fun requestEndpointIsStartPlusDelta() {
        val request = ScrollGestureRequest(x = 100f, y = 200f, dx = 10f, dy = -30f)
        assertEquals(100f, request.startX)
        assertEquals(200f, request.startY)
        assertEquals(110f, request.endX)
        assertEquals(170f, request.endY)
    }

    @Test fun afterDrainAccumulatorCanStartFreshForANewBurst() {
        val accumulator = ScrollGestureAccumulator()
        accumulator.accumulate(dx = 0f, dy = 5f, x = 0f, y = 0f)
        accumulator.drain()
        accumulator.accumulate(dx = 0f, dy = 7f, x = 1f, y = 1f)
        val request = accumulator.drain()
        assertEquals(7f, request?.dy)
        assertEquals(1f, request?.x)
    }
}
