package com.lamontphillips.metadisplay.transporttest;

/** Rational frame clock. All methods belong to one producer thread; no Android dependencies. */
public final class CameraFramePacer {
  private static final long SECOND = 1_000_000_000L;
  public static final class Tick {
    public final long index;
    public final long deadlineNs;
    public final long presentationTimeNs;
    /** Lateness against the original next tick, before any skipped-tick adjustment. */
    public final long latenessNs;
    public final long skippedTicks;
    public final boolean stalled;
    private Tick(long index, long deadlineNs, long pts, long lateness, long skipped, boolean stalled) {
      this.index = index;
      this.deadlineNs = deadlineNs;
      this.presentationTimeNs = pts;
      this.latenessNs = lateness;
      this.skippedTicks = skipped;
      this.stalled = stalled;
    }
  }

  private final int fps;
  private final long stallNs;
  private boolean started;
  private long epochNs;
  private long nextIndex;
  private long skippedTicks;
  private long maxLatenessNs;

  public CameraFramePacer(int fps, long stallNs) {
    if (fps < 1 || fps > 240 || stallNs <= 0) throw new IllegalArgumentException("Invalid frame clock");
    this.fps = fps;
    this.stallNs = stallNs;
  }

  public void start(long epochNs) {
    if (started) throw new IllegalStateException("Frame clock already started");
    this.epochNs = epochNs;
    started = true;
  }

  public void reset() {
    started = false;
    epochNs = 0;
    nextIndex = 0;
    skippedTicks = 0;
    maxLatenessNs = 0;
  }

  public boolean isStarted() { return started; }
  public long getSkippedTicks() { return skippedTicks; }
  public long getMaxLatenessNs() { return maxLatenessNs; }
  public long getNextDeadlineNs() {
    if (!started) throw new IllegalStateException("Frame clock not started");
    // nanoTime arithmetic deliberately wraps; elapsed comparisons use subtraction.
    return epochNs + timestamp(nextIndex);
  }

  public Tick poll(long nowNs) {
    if (!started) return null;
    long elapsed = nowNs - epochNs;
    long nextPts = timestamp(nextIndex);
    if (elapsed < 0 || elapsed < nextPts) return null;
    long lateness = elapsed - nextPts;
    maxLatenessNs = Math.max(maxLatenessNs, lateness);
    if (lateness >= stallNs) {
      return new Tick(nextIndex, epochNs + nextPts, nextPts, lateness, 0, true);
    }
    // Inverse of floor(index*SECOND/fps), including the exact fractional-nanosecond boundary.
    long seconds = elapsed / SECOND;
    long remainder = elapsed % SECOND;
    long latest = Math.addExact(Math.multiplyExact(seconds, fps),
        ((remainder + 1) * fps - 1) / SECOND);
    long skipped = latest - nextIndex;
    skippedTicks = Math.addExact(skippedTicks, skipped);
    long pts = timestamp(latest);
    nextIndex = Math.addExact(latest, 1);
    return new Tick(latest, epochNs + pts, pts, lateness, skipped, false);
  }

  private long timestamp(long index) {
    return Math.addExact(Math.multiplyExact(index / fps, SECOND), (index % fps) * SECOND / fps);
  }
}
