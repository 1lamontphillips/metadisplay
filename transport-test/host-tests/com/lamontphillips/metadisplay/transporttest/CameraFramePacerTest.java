package com.lamontphillips.metadisplay.transporttest;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Deterministic camera-clock tests: no Android, sleeps, camera data, or wall-clock assumptions. */
public final class CameraFramePacerTest {
  private static final long SECOND = 1_000_000_000L;
  private static final long STALL = 500_000_000L;
  private static final long EPOCH = 8_000_000_000L;

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    tests.add(test("constructor rejects invalid frame rates and stall budgets", CameraFramePacerTest::invalidConstructor));
    tests.add(test("first real image establishes epoch after arbitrary warmup", CameraFramePacerTest::firstImageEpoch));
    tests.add(test("tick zero submits once at its epoch", CameraFramePacerTest::firstTick));
    tests.add(test("poll before epoch cannot submit or affect counters", CameraFramePacerTest::beforeEpoch));
    for (long index : new long[] {1, 2, 23, 24, 25, 47, 48, 479, 480}) {
      tests.add(test("rational 24Hz deadline boundary for tick " + index,
          () -> rationalBoundary(index)));
    }
    tests.add(test("five minutes of 24Hz ticks have no accumulated truncation drift", CameraFramePacerTest::longAnchor));
    tests.add(test("small lateness retains scheduled PTS rather than arrival time", CameraFramePacerTest::slightlyLate));
    tests.add(test("late poll emits latest due tick with elapsed timestamp gaps", CameraFramePacerTest::skippedTicks));
    tests.add(test("late poll never drains a catch-up queue", CameraFramePacerTest::noCatchUp));
    tests.add(test("cumulative skip and maximum lateness metrics survive on-time polls", CameraFramePacerTest::counters));
    tests.add(test("one nanosecond below stall threshold may select latest due tick", CameraFramePacerTest::belowStall));
    tests.add(test("exact stall threshold is checked before skipping ticks", () -> stallBoundary(0)));
    tests.add(test("one nanosecond above stall threshold is still guarded", () -> stallBoundary(1)));
    tests.add(test("large render gap cannot be hidden by a current tick", CameraFramePacerTest::largeGap));
    tests.add(test("start twice cannot silently rebase an active clock", CameraFramePacerTest::doubleStart));
    tests.add(test("reset clears stalled state and all counters for a new first image", CameraFramePacerTest::reset));
    tests.add(test("negative nanoTime epoch is a valid active clock", CameraFramePacerTest::negativeEpoch));
    tests.add(test("nanoTime wrap preserves deadlines and nonnegative relative PTS", CameraFramePacerTest::clockWrap));
    tests.add(test("clock rollback cannot repeat an already consumed tick", CameraFramePacerTest::rollback));
    tests.add(test("relative PTS overflow is guarded instead of wrapping", CameraFramePacerTest::overflow));
    tests.add(test("generic valid frame rates retain independent rational anchors", CameraFramePacerTest::otherRates));
    int failures = 0;
    for (NamedCase test : tests) {
      try {
        test.body.run();
        System.out.println("PASS " + test.name);
      } catch (Throwable failure) {
        failures++;
        System.err.println("FAIL " + test.name + ": " + failure);
        failure.printStackTrace(System.err);
      }
    }
    System.out.println("CameraFramePacerTest: " + (tests.size() - failures) + "/"
        + tests.size() + " passed; " + failures + " failed");
    if (failures != 0) System.exit(1);
  }

  private static void invalidConstructor() {
    for (int fps : new int[] {-1, 0, 241, Integer.MAX_VALUE}) {
      expect(IllegalArgumentException.class, () -> new CameraFramePacer(fps, STALL));
    }
    for (long stall : new long[] {0, -1, Long.MIN_VALUE}) {
      expect(IllegalArgumentException.class, () -> new CameraFramePacer(24, stall));
    }
  }

  private static void firstImageEpoch() {
    CameraFramePacer pacer = new CameraFramePacer(24, STALL);
    for (long warmup : new long[] {0, SECOND, 20 * SECOND, 70 * SECOND}) {
      check(pacer.poll(warmup) == null, "a clock without a real image must remain inactive");
    }
    check(!pacer.isStarted(), "warmup must not start the frame clock");
    equal(0, pacer.getSkippedTicks(), "warmup skip count");
    equal(0, pacer.getMaxLatenessNs(), "warmup lateness");
    expect(IllegalStateException.class, pacer::getNextDeadlineNs);
    long firstImage = 71 * SECOND + 123;
    pacer.start(firstImage);
    tick(pacer.poll(firstImage), 0, firstImage, 0, 0, 0);
    equal(firstImage + time(1, 24), pacer.getNextDeadlineNs(), "first image anchors all later deadlines");
  }

  private static void firstTick() {
    CameraFramePacer pacer = started(EPOCH);
    tick(pacer.poll(EPOCH), 0, EPOCH, 0, 0, 0);
    check(pacer.poll(EPOCH) == null, "same time must not submit tick zero twice");
    equal(EPOCH + time(1, 24), pacer.getNextDeadlineNs(), "next rational deadline");
  }

  private static void beforeEpoch() {
    CameraFramePacer pacer = started(EPOCH);
    check(pacer.poll(EPOCH - 1) == null, "frame cannot be emitted before epoch");
    equal(EPOCH, pacer.getNextDeadlineNs(), "early poll retains deadline");
    equal(0, pacer.getMaxLatenessNs(), "early poll cannot count lateness");
    tick(pacer.poll(EPOCH), 0, EPOCH, 0, 0, 0);
  }

  private static void rationalBoundary(long selected) {
    CameraFramePacer pacer = started(EPOCH);
    // Advance naturally: this tests the boundary, independently of the skipped-tick algorithm.
    for (long index = 0; index < selected; index++) {
      long pts = time(index, 24);
      tick(pacer.poll(EPOCH + pts), index, EPOCH + pts, pts, 0, 0);
    }
    long pts = time(selected, 24);
    check(pacer.poll(EPOCH + pts - 1) == null, "not due one nanosecond early");
    tick(pacer.poll(EPOCH + pts), selected, EPOCH + pts, pts, 0, 0);
    check(pacer.poll(EPOCH + pts + 1) == null, "consumed tick cannot reappear one nanosecond later");
    equal(EPOCH + time(selected + 1, 24), pacer.getNextDeadlineNs(), "deadline remains anchored");
  }

  private static void longAnchor() {
    CameraFramePacer pacer = started(EPOCH);
    long previous = -1;
    for (long index = 0; index <= 24L * 300; index++) {
      long expected = time(index, 24);
      CameraFramePacer.Tick value = pacer.poll(EPOCH + expected);
      tick(value, index, EPOCH + expected, expected, 0, 0);
      check(value.presentationTimeNs > previous, "PTS remains strictly increasing");
      if (index % 24 == 0) equal(index / 24 * SECOND, expected, "whole seconds are exact");
      previous = value.presentationTimeNs;
    }
    equal(0, pacer.getSkippedTicks(), "on-time clock never skips");
  }

  private static void slightlyLate() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    long pts = time(1, 24);
    long late = 2_345_678;
    tick(pacer.poll(EPOCH + pts + late), 1, EPOCH + pts, pts, late, 0);
    equal(EPOCH + time(2, 24), pacer.getNextDeadlineNs(), "lateness does not rebase future deadlines");
  }

  private static void skippedTicks() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    long elapsed = 490_000_000;
    long selected = latest(elapsed, 24);
    CameraFramePacer.Tick value = pacer.poll(EPOCH + elapsed);
    tick(value, selected, EPOCH + time(selected, 24), time(selected, 24),
        elapsed - time(1, 24), selected - 1);
    check(value.presentationTimeNs > 400_000_000, "elapsed media time cannot be compressed to output frame count");
    equal(selected - 1, pacer.getSkippedTicks(), "all missed scheduled ticks counted");
  }

  private static void noCatchUp() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    CameraFramePacer.Tick value = pacer.poll(EPOCH + 150_000_000);
    equal(3, value.index, "only latest due frame selected");
    for (int i = 0; i < 20; i++) {
      check(pacer.poll(EPOCH + 150_000_000) == null, "late polls cannot drain stale frames");
    }
    long next = time(4, 24);
    check(pacer.poll(EPOCH + next - 1) == null, "next decision waits for future boundary");
    tick(pacer.poll(EPOCH + next), 4, EPOCH + next, next, 0, 0);
  }

  private static void counters() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    pacer.poll(EPOCH + 150_000_000); // Consume tick 3, skipping 1 and 2.
    CameraFramePacer.Tick second = pacer.poll(EPOCH + 350_000_000); // Tick 8; skip 4..7.
    equal(8, second.index, "second latest due index");
    equal(4, second.skippedTicks, "skip count belongs to this poll");
    equal(6, pacer.getSkippedTicks(), "cumulative skips");
    long max = 350_000_000 - time(4, 24);
    equal(max, pacer.getMaxLatenessNs(), "original-next-deadline maximum");
    pacer.poll(EPOCH + time(9, 24));
    equal(max, pacer.getMaxLatenessNs(), "on-time tick cannot clear history");
    equal(6, pacer.getSkippedTicks(), "on-time tick cannot clear skipped history");
  }

  private static void belowStall() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    long now = EPOCH + time(1, 24) + STALL - 1;
    CameraFramePacer.Tick value = pacer.poll(now);
    check(value != null && !value.stalled, "strictly below stall boundary remains a valid decision");
    equal(STALL - 1, value.latenessNs, "raw lateness is retained before skip");
    equal(latest(now - EPOCH, 24), value.index, "latest elapsed tick selected");
    check(pacer.poll(now) == null, "boundary-adjacent decision cannot repeat");
  }

  private static void stallBoundary(long extra) {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    long original = pacer.getNextDeadlineNs();
    CameraFramePacer.Tick value = pacer.poll(original + STALL + extra);
    check(value != null && value.stalled, "stall must be reported before selecting a recent tick");
    equal(STALL + extra, value.latenessNs, "unadjusted missed-deadline time");
    equal(original, pacer.getNextDeadlineNs(), "stalled clock cannot advance");
    equal(0, pacer.getSkippedTicks(), "stalled decision is not a successful skipped render");
    equal(STALL + extra, pacer.getMaxLatenessNs(), "stall severity retained");
    check(pacer.poll(original + STALL + extra).stalled, "repeated poll cannot hide the stall");
  }

  private static void largeGap() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    long now = EPOCH + 2 * SECOND;
    CameraFramePacer.Tick value = pacer.poll(now);
    check(value.stalled, "multi-second gap is not silently converted into an on-time frame");
    equal(2 * SECOND - time(1, 24), value.latenessNs, "entire render gap counted");
    equal(1, value.index, "guard refers to original unrendered tick");
  }

  private static void doubleStart() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    expect(IllegalStateException.class, () -> pacer.start(EPOCH + SECOND));
    equal(EPOCH + time(1, 24), pacer.getNextDeadlineNs(), "failed second start cannot change epoch");
  }

  private static void reset() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    pacer.poll(EPOCH + 150_000_000);
    pacer.poll(EPOCH + 2 * SECOND);
    pacer.reset();
    check(!pacer.isStarted() && pacer.poll(EPOCH + 10 * SECOND) == null, "reset returns to real-image warmup");
    equal(0, pacer.getSkippedTicks(), "reset skips");
    equal(0, pacer.getMaxLatenessNs(), "reset lateness");
    expect(IllegalStateException.class, pacer::getNextDeadlineNs);
    long newEpoch = EPOCH + 30 * SECOND;
    pacer.start(newEpoch);
    tick(pacer.poll(newEpoch), 0, newEpoch, 0, 0, 0);
  }

  private static void negativeEpoch() {
    long epoch = -8 * SECOND;
    CameraFramePacer pacer = started(epoch);
    tick(pacer.poll(epoch), 0, epoch, 0, 0, 0);
    tick(pacer.poll(epoch + time(1, 24)), 1, epoch + time(1, 24), time(1, 24), 0, 0);
  }

  private static void clockWrap() {
    long epoch = Long.MAX_VALUE - 20_000_000;
    CameraFramePacer pacer = started(epoch);
    pacer.poll(epoch);
    for (long index = 1; index <= 30; index++) {
      long pts = time(index, 24);
      long deadline = epoch + pts; // System.nanoTime is a wrapping signed counter.
      check(pacer.poll(deadline - 1) == null, "wrapped boundary cannot be early");
      tick(pacer.poll(deadline), index, deadline, pts, 0, 0);
    }
  }

  private static void rollback() {
    CameraFramePacer pacer = started(EPOCH);
    pacer.poll(EPOCH);
    pacer.poll(EPOCH + 150_000_000);
    check(pacer.poll(EPOCH + 100_000_000) == null, "backward clock reading cannot duplicate an old frame");
    check(pacer.poll(EPOCH - 1) == null, "negative elapsed time cannot emit");
    equal(EPOCH + time(4, 24), pacer.getNextDeadlineNs(), "rollback leaves future deadline intact");
  }

  private static void overflow() {
    CameraFramePacer pacer = new CameraFramePacer(24, Long.MAX_VALUE);
    pacer.start(0);
    long now = Long.MAX_VALUE - 1;
    CameraFramePacer.Tick value = pacer.poll(now);
    check(value != null && !value.stalled && value.presentationTimeNs >= 0,
        "largest valid relative decision cannot wrap negative");
    equal(time(value.index, 24), value.presentationTimeNs, "large valid rational PTS");
    expect(ArithmeticException.class, pacer::getNextDeadlineNs);
  }

  private static void otherRates() {
    for (int fps : new int[] {1, 23, 25, 30, 60, 240}) {
      CameraFramePacer pacer = new CameraFramePacer(fps, STALL);
      pacer.start(EPOCH);
      for (long index = 0; index <= fps * 3L; index++) {
        long pts = time(index, fps);
        tick(pacer.poll(EPOCH + pts), index, EPOCH + pts, pts, 0, 0);
      }
    }
  }

  private static CameraFramePacer started(long epoch) {
    CameraFramePacer pacer = new CameraFramePacer(24, STALL);
    pacer.start(epoch);
    return pacer;
  }

  private static long time(long index, int fps) {
    return BigInteger.valueOf(index).multiply(BigInteger.valueOf(SECOND))
        .divide(BigInteger.valueOf(fps)).longValueExact();
  }

  private static long latest(long elapsed, int fps) {
    // Reference search over the rational grid, not the production inverse arithmetic.
    long low = 0;
    long high = elapsed / SECOND * fps + fps;
    while (low < high) {
      long middle = low + (high - low + 1) / 2;
      if (time(middle, fps) <= elapsed) low = middle;
      else high = middle - 1;
    }
    return low;
  }

  private static void tick(CameraFramePacer.Tick value, long index, long deadline,
      long pts, long lateness, long skipped) {
    check(value != null && !value.stalled, "expected one valid render decision");
    equal(index, value.index, "tick index");
    equal(deadline, value.deadlineNs, "absolute deadline");
    equal(pts, value.presentationTimeNs, "scheduled relative PTS");
    equal(lateness, value.latenessNs, "pre-skip lateness");
    equal(skipped, value.skippedTicks, "per-poll skipped ticks");
  }

  private static void equal(long expected, long actual, String message) {
    check(expected == actual, message + ": expected " + expected + ", got " + actual);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void expect(Class<? extends Throwable> type, Runnable operation) {
    try { operation.run(); }
    catch (Throwable failure) {
      if (type.isInstance(failure)) return;
      throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure, failure);
    }
    throw new AssertionError("Expected " + type.getSimpleName());
  }

  private static NamedCase test(String name, Runnable body) { return new NamedCase(name, body); }
  private static final class NamedCase {
    final String name;
    final Runnable body;
    NamedCase(String name, Runnable body) { this.name = name; this.body = body; }
  }
}
