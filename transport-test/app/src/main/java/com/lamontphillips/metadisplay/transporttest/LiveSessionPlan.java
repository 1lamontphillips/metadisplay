package com.lamontphillips.metadisplay.transporttest;

/** Immutable quotas for one truthful, finite MP4 response. Controls retain the short plan. */
public final class LiveSessionPlan {
  public static final LiveSessionPlan SHORT = new LiveSessionPlan(
      20, LiveVideoConfig.SLOT_BYTES, 35000, 75000, 75000);
  public static final LiveSessionPlan ONE_MINUTE = new LiveSessionPlan(
      60, LiveVideoConfig.SLOT_BYTES, 90000, 150000, 150000);

  private final int fragmentCount;
  private final int slotBytes;
  private final long captureLimitMs;
  private final long testTimeoutMs;
  private final long httpResponseLimitMs;

  private LiveSessionPlan(int fragmentCount, int slotBytes, long captureLimitMs,
      long testTimeoutMs, long httpResponseLimitMs) {
    FixedBudgetMp4.validateBudget(fragmentCount, slotBytes);
    if (captureLimitMs < fragmentCount * 1000L || captureLimitMs >= testTimeoutMs
        || httpResponseLimitMs <= 0 || httpResponseLimitMs > testTimeoutMs) {
      throw new IllegalArgumentException("Invalid finite session limits");
    }
    this.fragmentCount = fragmentCount;
    this.slotBytes = slotBytes;
    this.captureLimitMs = captureLimitMs;
    this.testTimeoutMs = testTimeoutMs;
    this.httpResponseLimitMs = httpResponseLimitMs;
  }

  public int getFragmentCount() { return fragmentCount; }
  public int getSlotBytes() { return slotBytes; }
  public int getNominalSeconds() { return fragmentCount; }
  public long getSlotPayloadBytes() { return (long) fragmentCount * slotBytes; }
  public long getCaptureLimitMs() { return captureLimitMs; }
  public long getTestTimeoutMs() { return testTimeoutMs; }
  public long getHttpResponseLimitMs() { return httpResponseLimitMs; }

  /** Check producer and cache quotas before camera access, never after partial output. */
  public void validateBuffer(BoundedMediaBuffer buffer) {
    if (buffer == null || buffer.getSlotCount() != fragmentCount
        || buffer.getSlotBytes() != slotBytes) {
      throw new IllegalArgumentException("MEDIA_BUDGET_MISMATCH");
    }
  }
}
