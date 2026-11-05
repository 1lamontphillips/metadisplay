package com.lamontphillips.metadisplay.transporttest;

/** Deliberately bounded live experiment; the planned response size is exact, never estimated. */
public final class LiveVideoConfig {
  private LiveVideoConfig() {}
  public static final int WIDTH = 266;
  public static final int HEIGHT = 150;
  public static final int FPS = 24;
  public static final int BITRATE = 120000;
  public static final int FRAGMENTS = 20;
  public static final int SLOT_BYTES = 65536;
  public static final long FIXTURE_CADENCE_MS = 1000;
}
