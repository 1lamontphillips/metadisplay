package com.lamontphillips.metadisplay.transporttest;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Policy/cache coupling checks using public numeric plans and synthetic media bytes. */
public final class LiveSessionPlanTest {
  private LiveSessionPlanTest() {}

  public static void main(String[] args) {
    List<NamedCase> cases = new ArrayList<>();
    cases.add(new NamedCase("short plan preserves fixture quota and legacy deadlines", LiveSessionPlanTest::shortBaseline));
    cases.add(new NamedCase("one-minute plan fits bounded cache and playback lifetime", LiveSessionPlanTest::minuteBounds));
    cases.add(new NamedCase("immutable quotas survive initialization and cleanup", LiveSessionPlanTest::immutableQuotas));
    cases.add(new NamedCase("each plan accepts its matching cache", LiveSessionPlanTest::matchingBuffers));
    cases.add(new NamedCase("null cache rejected before capture", () -> rejects(() -> LiveSessionPlan.ONE_MINUTE.validateBuffer(null))));
    cases.add(new NamedCase("cross-plan quota mismatch rejected before output", LiveSessionPlanTest::quotaMismatch));
    cases.add(new NamedCase("slot-size mismatch rejected before output", LiveSessionPlanTest::slotMismatch));
    cases.add(new NamedCase("sixty-slot cache retains exact bytes and true EOF", LiveSessionPlanTest::minuteExactBody));
    cases.add(new NamedCase("one-minute completion requires all sixty slots", LiveSessionPlanTest::minuteCompletion));
    cases.add(new NamedCase("original32MiB slot-budget limit remains enforced", LiveSessionPlanTest::originalBudgetBound));
    cases.add(new NamedCase("five-minute plan fits existing memory and playback bounds", LiveSessionPlanTest::fiveMinuteBounds));
    cases.add(new NamedCase("five-minute quota rejects shorter and differently sized caches", LiveSessionPlanTest::fiveMinuteMismatch));
    cases.add(new NamedCase("300-slot cache has exact bytes and true EOF", LiveSessionPlanTest::fiveMinuteExactBody));
    cases.add(new NamedCase("five-minute completion requires all300 slots", LiveSessionPlanTest::fiveMinuteCompletion));
    cases.add(new NamedCase("five-minute quota metadata remains after memory cleanup", LiveSessionPlanTest::fiveMinuteCleanup));
    int failures = 0;
    for (NamedCase test : cases) {
      try {
        test.body.run();
        System.out.println("PASS " + test.name);
      } catch (Throwable problem) {
        failures++;
        System.err.println("FAIL " + test.name + ": " + problem);
        problem.printStackTrace(System.err);
      }
    }
    System.out.println("LiveSessionPlanTest: " + (cases.size() - failures) + "/" + cases.size()
        + " passed; " + failures + " failed");
    if (failures != 0) System.exit(1);
  }

  private static void shortBaseline() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.SHORT;
    equal(LiveVideoConfig.FRAGMENTS, plan.getFragmentCount(), "fixture remains the short quota");
    equal(20, plan.getNominalSeconds(), "short nominal duration retained");
    equal(LiveVideoConfig.SLOT_BYTES, plan.getSlotBytes(), "fixture and camera slot layout agree");
    equal(35000, plan.getCaptureLimitMs(), "legacy camera hard stop");
    equal(75000, plan.getTestTimeoutMs(), "legacy overall test limit");
    equal(75000, plan.getHttpResponseLimitMs(), "legacy HTTP response limit");
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(LiveVideoConfig.FRAGMENTS, LiveVideoConfig.SLOT_BYTES)) {
      plan.validateBuffer(source);
      equal(0, source.getProducedBytes(), "checking policy does not capture or emit media");
    }
  }

  private static void minuteBounds() {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    equal(60, plan.getFragmentCount(), "one-minute continuous fragment budget");
    equal(60, plan.getNominalSeconds(), "nominal one-second GOP duration");
    equal(3932160, plan.getSlotPayloadBytes(), "sixty exact slots");
    equal(65536, plan.getSlotBytes(), "existing fragment format retained");
    check(plan.getSlotPayloadBytes() <= 32L * 1024 * 1024, "existing slot-memory bound");
    check(plan.getSlotPayloadBytes() + FixedBudgetMp4.MAX_INITIALIZATION_BYTES <= Integer.MAX_VALUE,
        "largest permitted init plus media fits the cache's array index");
    check(plan.getCaptureLimitMs() >= plan.getNominalSeconds() * 1000L,
        "camera can finish its nominal quota");
    check(plan.getCaptureLimitMs() < plan.getTestTimeoutMs(), "playback can outlive active capture");
    check(plan.getTestTimeoutMs() >= 2 * plan.getNominalSeconds() * 1000L,
        "test allows a client that waits for production before playing");
    equal(90000, plan.getCaptureLimitMs(), "bounded long capture deadline");
    equal(150000, plan.getTestTimeoutMs(), "bounded long test deadline");
    equal(150000, plan.getHttpResponseLimitMs(), "bounded long response deadline");
  }

  private static void immutableQuotas() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
    equal(60, source.getSlotCount(), "quota before first bytes");
    equal(65536, source.getSlotBytes(), "slot size before first bytes");
    source.onInitialization(bytes(703, 3));
    source.close();
    equal(60, source.getSlotCount(), "quota metadata survives cleanup");
    equal(65536, source.getSlotBytes(), "slot metadata survives cleanup");
    plan.validateBuffer(source);
    try {
      source.readAt(0, new byte[1], 0, 1, 0);
      throw new AssertionError("closed camera cache remained readable");
    } catch (IOException expected) { }
  }

  private static void matchingBuffers() throws Exception {
    for (LiveSessionPlan plan : new LiveSessionPlan[] {
        LiveSessionPlan.SHORT, LiveSessionPlan.ONE_MINUTE, LiveSessionPlan.FIVE_MINUTES}) {
      try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes())) {
        plan.validateBuffer(source);
        source.onInitialization(bytes(17, 2));
        equal(17 + plan.getSlotPayloadBytes(), source.getContentLength(),
            "exact future HTTP size follows actual initialization");
      }
    }
  }

  private static void quotaMismatch() throws Exception {
    try (BoundedMediaBuffer shortSource = new BoundedMediaBuffer(20, 65536);
        BoundedMediaBuffer longSource = new BoundedMediaBuffer(60, 65536)) {
      rejects(() -> LiveSessionPlan.ONE_MINUTE.validateBuffer(shortSource));
      rejects(() -> LiveSessionPlan.SHORT.validateBuffer(longSource));
      equal(0, shortSource.getProducedBytes(), "rejected short cache has no media");
      equal(0, longSource.getProducedBytes(), "rejected long cache has no media");
    }
  }

  private static void slotMismatch() throws Exception {
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(60, 32768)) {
      rejects(() -> LiveSessionPlan.ONE_MINUTE.validateBuffer(source));
      equal(0, source.getContentLength(), "mismatch detected before a declared response");
    }
  }

  private static void minuteExactBody() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    byte[] init = bytes(703, 7);
    MessageDigest expected = MessageDigest.getInstance("SHA-256");
    MessageDigest actual = MessageDigest.getInstance("SHA-256");
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes())) {
      plan.validateBuffer(source);
      source.onInitialization(init);
      equal(3932863, source.getContentLength(), "actual703-byte init plus60slots");
      expected.update(init);
      for (int i = 0; i < plan.getFragmentCount(); i++) {
        byte[] slot = bytes(plan.getSlotBytes(), i + 1);
        expected.update(slot);
        source.onFragment(slot);
      }
      source.onComplete();
      byte[] chunk = new byte[8192];
      long offset = 0;
      int amount;
      while ((amount = source.readAt(offset, chunk, 0, chunk.length, 0)) >= 0) {
        check(amount > 0, "reader advances until real EOF");
        actual.update(chunk, 0, amount);
        offset += amount;
      }
      equal(source.getContentLength(), offset, "all bytes are declared and readable");
      check(Arrays.equals(expected.digest(), actual.digest()), "all sixty synthetic slots retained exactly");
      equal(60, source.getCompletedSlots(), "sixty fragments complete");
      check(source.isComplete(), "complete only at the true end");
      try {
        source.onFragment(new byte[plan.getSlotBytes()]);
        throw new AssertionError("extra media accepted beyond declared byte budget");
      } catch (IOException expectedOverflow) { }
      equal(offset, source.getProducedBytes(), "rejected extra slot does not exceed Content-Length");
    }
  }

  private static void minuteCompletion() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes())) {
      source.onInitialization(bytes(5, 1));
      byte[] slot = new byte[plan.getSlotBytes()];
      for (int i = 0; i < 59; i++) source.onFragment(slot);
      try {
        source.onComplete();
        throw new AssertionError("59-slot capture falsely declared complete");
      } catch (IOException expected) { }
      check(!source.isComplete(), "missing last fragment remains incomplete");
      source.onFragment(slot);
      source.onComplete();
      check(source.isComplete(), "last promised fragment permits completion");
    }
  }

  private static void originalBudgetBound() throws Exception {
    rejects(() -> new BoundedMediaBuffer(513, 65536));
    rejects(() -> new BoundedMediaBuffer(60, 0));
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(60, 65536)) {
      LiveSessionPlan.ONE_MINUTE.validateBuffer(source);
    }
  }

  private static void fiveMinuteBounds() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    equal(300, plan.getFragmentCount(), "five-minute continuous fragment budget");
    equal(300, plan.getNominalSeconds(), "nominal300-second session");
    equal(65536, plan.getSlotBytes(), "same proven fragment format");
    equal(19660800, plan.getSlotPayloadBytes(), "300 exact slots without bitrate estimation");
    check(plan.getSlotPayloadBytes() <= 32L * 1024 * 1024, "existing slot-memory cap preserved");
    check(plan.getSlotPayloadBytes() + FixedBudgetMp4.MAX_INITIALIZATION_BYTES <= Integer.MAX_VALUE,
        "largest allowed initialization plus five-minute budget fits indexed cache");
    equal(360000, plan.getCaptureLimitMs(), "finite capture guard beyond nominal duration");
    equal(660000, plan.getTestTimeoutMs(), "finite delayed playback limit");
    equal(660000, plan.getHttpResponseLimitMs(), "finite full-response limit");
    check(plan.getCaptureLimitMs() >= plan.getNominalSeconds() * 1000L,
        "camera can produce entire nominal session");
    check(plan.getCaptureLimitMs() < plan.getTestTimeoutMs(), "playback can finish after camera stops");
    check(plan.getTestTimeoutMs() >= 2L * plan.getNominalSeconds() * 1000,
        "overall limit allows finite full-production buffering plus playback");
  }

  private static void fiveMinuteMismatch() throws Exception {
    for (int count : new int[] {20, 60, 299, 301}) {
      try (BoundedMediaBuffer source = new BoundedMediaBuffer(count, 65536)) {
        rejects(() -> LiveSessionPlan.FIVE_MINUTES.validateBuffer(source));
        equal(0, source.getProducedBytes(), "quota mismatch rejected before media output");
      }
    }
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(300, 32768)) {
      rejects(() -> LiveSessionPlan.FIVE_MINUTES.validateBuffer(source));
      equal(0, source.getContentLength(), "slot-size mismatch has no declared response");
    }
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(300, 65536)) {
      rejects(() -> LiveSessionPlan.SHORT.validateBuffer(source));
      rejects(() -> LiveSessionPlan.ONE_MINUTE.validateBuffer(source));
    }
  }

  private static void fiveMinuteExactBody() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    byte[] init = bytes(703, 11);
    MessageDigest expected = MessageDigest.getInstance("SHA-256");
    MessageDigest actual = MessageDigest.getInstance("SHA-256");
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes())) {
      plan.validateBuffer(source);
      source.onInitialization(init);
      equal(19661503, source.getContentLength(), "actual initialization plus300slots");
      expected.update(init);
      for (int i = 0; i < plan.getFragmentCount(); i++) {
        byte[] slot = wideSlot(plan.getSlotBytes(), i + 1);
        expected.update(slot);
        source.onFragment(slot);
      }
      source.onComplete();
      byte[] chunk = new byte[16384];
      long offset = 0;
      int amount;
      while ((amount = source.readAt(offset, chunk, 0, chunk.length, 0)) >= 0) {
        check(amount > 0, "reader advances until true response end");
        actual.update(chunk, 0, amount);
        offset += amount;
      }
      check(Arrays.equals(expected.digest(), actual.digest()), "all300 produced slots retain exact hashes");
      equal(source.getContentLength(), offset, "true EOF exactly matches Content-Length");
      equal(300, source.getCompletedSlots(), "five-minute quota complete");
      check(source.isComplete(), "completed source distinct from intermediate production");
      try {
        source.onFragment(new byte[plan.getSlotBytes()]);
        throw new AssertionError("slot301 exceeded the declared response");
      } catch (IOException expectedOverflow) { }
      equal(offset, source.getProducedBytes(), "overflow cannot increase the honest response size");
    }
  }

  private static void fiveMinuteCompletion() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes())) {
      source.onInitialization(bytes(7, 4));
      byte[] slot = new byte[plan.getSlotBytes()];
      for (int i = 0; i < 299; i++) source.onFragment(slot);
      try {
        source.onComplete();
        throw new AssertionError("299 slots falsely completed a300-slot response");
      } catch (IOException expected) { }
      check(!source.isComplete(), "unproduced final fragment remains incomplete");
      source.onFragment(slot);
      source.onComplete();
      check(source.isComplete(), "final promised fragment completes the response");
    }
  }

  private static void fiveMinuteCleanup() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
    source.onInitialization(bytes(703, 2));
    source.onFragment(bytes(plan.getSlotBytes(), 3));
    source.close();
    check(source.isClosed(), "large cache closed");
    equal(300, source.getSlotCount(), "immutable large quota survives cleanup");
    equal(65536, source.getSlotBytes(), "slot metadata survives cleanup");
    equal(19661503, source.getContentLength(), "declared length remains diagnostic metadata");
    try {
      source.readAt(0, new byte[1], 0, 1, 0);
      throw new AssertionError("closed five-minute camera cache remained readable");
    } catch (IOException expected) { }
  }

  private static byte[] bytes(int size, int seed) {
    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) result[i] = (byte) (i * 19 + seed);
    return result;
  }
  private static byte[] wideSlot(int size, int seed) {
    byte[] result = bytes(size, seed);
    for (int i = 0; i < 4; i++) result[i] = (byte) (seed >>> (24 - i * 8));
    return result;
  }
  private static void rejects(Case body) throws Exception {
    try { body.run(); throw new AssertionError("invalid session/cache combination accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private static void equal(long expected, long actual, String message) {
    if (expected != actual) throw new AssertionError(message + ": " + actual + " != " + expected);
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private interface Case { void run() throws Exception; }
  private static final class NamedCase {
    final String name;
    final Case body;
    NamedCase(String name, Case body) { this.name = name; this.body = body; }
  }
}
