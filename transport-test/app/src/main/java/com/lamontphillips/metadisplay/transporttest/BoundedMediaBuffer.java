package com.lamontphillips.metadisplay.transporttest;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Fixed-capacity, append-only live response cache. Camera bytes never leave memory here. */
public final class BoundedMediaBuffer implements FixedBudgetMp4.Sink, AutoCloseable {
  private final int slotCount;
  private final int slotBytes;
  private byte[] data;
  private long contentLength;
  private int produced;
  private int completedSlots;
  private boolean complete;
  private boolean closed;
  private String failure;

  public BoundedMediaBuffer(int slotCount, int slotBytes) {
    FixedBudgetMp4.validateBudget(slotCount, slotBytes);
    this.slotCount = slotCount;
    this.slotBytes = slotBytes;
  }

  @Override public synchronized void onInitialization(byte[] init) throws IOException {
    checkUsable();
    if (data != null || init == null || init.length == 0
        || init.length > FixedBudgetMp4.MAX_INITIALIZATION_BYTES) {
      throw new IOException("MEDIA_INITIALIZATION_INVALID");
    }
    contentLength = init.length + (long) slotCount * slotBytes;
    data = new byte[(int) contentLength];
    System.arraycopy(init, 0, data, 0, init.length);
    produced = init.length;
    notifyAll();
  }

  @Override public synchronized void onFragment(byte[] slot) throws IOException {
    checkUsable();
    if (data == null || complete || completedSlots >= slotCount
        || slot == null || slot.length != slotBytes) throw new IOException("MEDIA_SLOT_INVALID");
    System.arraycopy(slot, 0, data, produced, slot.length);
    produced += slot.length;
    completedSlots++;
    notifyAll();
  }

  @Override public synchronized void onComplete() throws IOException {
    checkUsable();
    if (data == null || completedSlots != slotCount || produced != contentLength) {
      throw new IOException("MEDIA_INCOMPLETE");
    }
    complete = true;
    notifyAll();
  }

  public synchronized boolean awaitInitialized(long timeoutMs)
      throws IOException, InterruptedException {
    long budget = waitBudget(timeoutMs);
    long begun = System.nanoTime();
    while (true) {
      checkUsable();
      if (data != null) return true;
      long remaining = budget - (System.nanoTime() - begun);
      if (remaining <= 0) return false;
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  public synchronized long getContentLength() { return contentLength; }
  public synchronized long getProducedBytes() { return produced; }
  public synchronized int getCompletedSlots() { return completedSlots; }
  public synchronized boolean isComplete() { return complete; }
  public synchronized boolean isClosed() { return closed; }
  public synchronized String getFailure() { return failure; }

  /** Each wait is bounded to at most 30 seconds. The caller may retry a timeout. */
  public synchronized int readAt(long offset, byte[] target, int off, int length, long waitMs)
      throws IOException, InterruptedException {
    if (target == null) throw new NullPointerException("target");
    if (off < 0 || length < 0 || off > target.length - length || offset < 0) {
      throw new IndexOutOfBoundsException();
    }
    long budget = waitBudget(waitMs);
    long begun = System.nanoTime();
    while (true) {
      checkUsable();
      if (length == 0) return 0;
      if (data != null && offset > contentLength) throw new IOException("MEDIA_OFFSET_INVALID");
      if (offset < produced) {
        int amount = (int) Math.min(length, produced - offset);
        System.arraycopy(data, (int) offset, target, off, amount);
        return amount;
      }
      if (complete && offset == produced) return -1;
      long remaining = budget - (System.nanoTime() - begun);
      if (remaining <= 0) throw new SocketTimeoutException("MEDIA_WAIT_TIMEOUT");
      TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
  }

  public synchronized void fail(String safeCode) {
    if (closed || failure != null) return;
    failure = safeCode != null && safeCode.matches("[A-Z][A-Z0-9_]{0,63}")
        ? safeCode : "MEDIA_FAILED";
    complete = false;
    notifyAll();
  }

  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    if (data != null) Arrays.fill(data, (byte) 0);
    data = null;
    notifyAll();
  }

  private void checkUsable() throws IOException {
    if (closed) throw new IOException("MEDIA_CLOSED");
    if (failure != null) throw new IOException(failure);
  }

  private static long waitBudget(long millis) {
    if (millis < 0) throw new IllegalArgumentException("Negative wait duration");
    return TimeUnit.MILLISECONDS.toNanos(Math.min(millis, 30000));
  }
}
