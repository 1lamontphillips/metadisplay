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
    for (LiveSessionPlan plan : new LiveSessionPlan[] {LiveSessionPlan.SHORT, LiveSessionPlan.ONE_MINUTE}) {
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

  private static byte[] bytes(int size, int seed) {
    byte[] result = new byte[size];
    for (int i = 0; i < size; i++) result[i] = (byte) (i * 19 + seed);
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
