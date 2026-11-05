package com.lamontphillips.metadisplay.transporttest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Public finite fixture paced through exactly the same slot transformer as live encoder output. */
public final class PaddedFixtureProducer implements AutoCloseable {
  private final byte[] fixture;
  private final BoundedMediaBuffer buffer;
  private final int slotCount;
  private final int slotBytes;
  private final long cadenceMs;
  private final Consumer<String> listener;
  private volatile boolean stopped;
  private boolean started;
  private boolean closed;
  private Thread worker;

  public PaddedFixtureProducer(byte[] fixture, BoundedMediaBuffer buffer,
      int slotCount, int slotBytes, long cadenceMs, Consumer<String> listener) {
    FixedBudgetMp4.validateBudget(slotCount, slotBytes);
    if (fixture == null || buffer == null || listener == null) throw new NullPointerException();
    if (fixture.length > 33 * 1024 * 1024 || cadenceMs < 0 || cadenceMs > 30000) {
      throw new IllegalArgumentException("Invalid fixture budget or cadence");
    }
    this.fixture = fixture.clone();
    this.buffer = buffer;
    this.slotCount = slotCount;
    this.slotBytes = slotBytes;
    this.cadenceMs = cadenceMs;
    this.listener = listener;
  }

  public synchronized void start() {
    if (closed) throw new IllegalStateException("FIXTURE_CLOSED");
    if (started) return;
    started = true;
    worker = new Thread(this::produce, "bounded-public-fixture");
    worker.setDaemon(true);
    worker.start();
  }

  private void produce() {
    final long begun = System.nanoTime();
    FixedBudgetMp4 channel = new FixedBudgetMp4(slotCount, slotBytes, new FixedBudgetMp4.Sink() {
      private int nextSlot;
      @Override public void onInitialization(byte[] init) throws IOException {
        if (stopped) throw new IOException("FIXTURE_STOPPED");
        buffer.onInitialization(init);
      }
      @Override public void onFragment(byte[] slot) throws IOException {
        long deadline = begun + TimeUnit.MILLISECONDS.toNanos(cadenceMs) * ++nextSlot;
        try {
          while (!stopped) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.sleep(remaining);
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("FIXTURE_STOPPED");
        }
        if (stopped) throw new IOException("FIXTURE_STOPPED");
        buffer.onFragment(slot);
      }
      @Override public void onComplete() throws IOException { buffer.onComplete(); }
    });
    try {
      emit("STARTED");
      channel.write(ByteBuffer.wrap(fixture));
      if (!channel.isComplete()) throw new IOException("FIXTURE_INCOMPLETE");
      if (!stopped) emit("COMPLETE");
    } catch (IOException | RuntimeException failure) {
      if (!stopped) {
        buffer.fail("FIXTURE_FAILED");
        emit("FAILED");
      }
    } finally {
      try {
        channel.close();
      } catch (IOException incomplete) {
        if (!stopped && buffer.getFailure() == null) {
          buffer.fail("FIXTURE_FAILED");
          emit("FAILED");
        }
      }
    }
  }

  private void emit(String status) {
    try { listener.accept(status); }
    catch (RuntimeException ignored) { /* A report listener cannot own the production lifetime. */ }
  }

  @Override public void close() {
    Thread toJoin;
    synchronized (this) {
      if (closed) return;
      closed = true;
      stopped = true;
      toJoin = worker;
    }
    if (!buffer.isComplete()) buffer.fail("FIXTURE_STOPPED");
    if (toJoin != null) {
      toJoin.interrupt();
      if (toJoin != Thread.currentThread()) {
        boolean interrupted = false;
        try { toJoin.join(2000); }
        catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
        if (toJoin.isAlive()) throw new IllegalStateException("FIXTURE_STOP_UNCONFIRMED");
      }
    }
    Arrays.fill(fixture, (byte) 0);
  }
}
