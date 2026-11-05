package com.lamontphillips.metadisplay.transporttest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded fMP4 transformer. Each moof/mdat pair occupies one exact, valid ISO-BMFF slot. */
public final class FixedBudgetMp4 implements WritableByteChannel {
  static final int MAX_INITIALIZATION_BYTES = 1024 * 1024;
  private static final long MAX_BUDGET_BYTES = 32L * 1024 * 1024;

  public interface Sink {
    void onInitialization(byte[] init) throws IOException;
    void onFragment(byte[] fullSlot) throws IOException;
    void onComplete() throws IOException;
  }

  private final int slotCount;
  private final int slotBytes;
  private final Sink sink;
  private final byte[] header = new byte[16];
  private final ByteArrayOutputStream initialization = new ByteArrayOutputStream();
  private final Map<Long, Long> defaultSizes = new HashMap<>();
  private final Map<Long, Long> defaultDurations = new HashMap<>();
  private final Map<Long, Long> decodeEnds = new HashMap<>();
  private int headerUsed;
  private int headerNeeded = 8;
  private byte[] box;
  private int boxUsed;
  private int boxHeader;
  private String boxType;
  private long boxStart;
  private byte[] moof;
  private long moofStart;
  private boolean sawFtyp;
  private boolean initialized;
  private volatile boolean open = true;
  private volatile boolean complete;
  private volatile int emittedSlots;
  private volatile long rawBytes;
  private IOException failure;

  public FixedBudgetMp4(int slotCount, int slotBytes, Sink sink) {
    validateBudget(slotCount, slotBytes);
    if (sink == null) throw new NullPointerException("sink");
    this.slotCount = slotCount;
    this.slotBytes = slotBytes;
    this.sink = sink;
  }

  static void validateBudget(int count, int bytes) {
    if (count <= 0 || bytes < 16 || bytes > 1024 * 1024
        || (long) count * bytes > MAX_BUDGET_BYTES) {
      throw new IllegalArgumentException("Invalid fixed media budget");
    }
  }

  @Override public boolean isOpen() { return open; }
  public boolean isComplete() { return complete; }
  public int getEmittedSlots() { return emittedSlots; }
  public long getRawBytes() { return rawBytes; }

  @Override public synchronized int write(ByteBuffer source) throws IOException {
    if (!open) throw new IOException("MP4_CLOSED");
    if (failure != null) throw new IOException("MP4_FAILED");
    int requested = source.remaining();
    try {
      while (source.hasRemaining()) {
        if (complete) {
          int ignored = source.remaining();
          source.position(source.limit());
          rawBytes += ignored;
          break;
        }
        if (box == null) {
          if (headerUsed == 0) boxStart = rawBytes;
          int amount = Math.min(source.remaining(), headerNeeded - headerUsed);
          source.get(header, headerUsed, amount);
          headerUsed += amount;
          rawBytes += amount;
          if (headerUsed < headerNeeded) continue;
          long length = u32(header, 0);
          if (length == 1 && headerNeeded == 8) {
            headerNeeded = 16;
            continue;
          }
          boxHeader = headerNeeded;
          if (length == 1) length = positive64(header, 8);
          boxType = type(header, 4);
          int limit = initialized ? slotBytes : MAX_INITIALIZATION_BYTES;
          if (length < boxHeader || length > limit) bad("MP4_BOX_SIZE");
          if (!initialized && initialization.size() + length > MAX_INITIALIZATION_BYTES) {
            bad("MP4_INIT_BUDGET");
          }
          if (initialized && moof != null && length + moof.length > slotBytes) {
            bad("MP4_SLOT_OVERFLOW");
          }
          box = new byte[(int) length];
          System.arraycopy(header, 0, box, 0, headerUsed);
          boxUsed = headerUsed;
        }
        int amount = Math.min(source.remaining(), box.length - boxUsed);
        source.get(box, boxUsed, amount);
        boxUsed += amount;
        rawBytes += amount;
        if (boxUsed == box.length) {
          handleBox();
          box = null;
          boxUsed = 0;
          headerUsed = 0;
          headerNeeded = 8;
        }
      }
      return requested;
    } catch (IOException invalid) {
      failure = invalid;
      throw invalid;
    } catch (RuntimeException invalid) {
      failure = new IOException("MP4_INVALID", invalid);
      throw failure;
    }
  }

  private void handleBox() throws IOException {
    if (!initialized) {
      if (!sawFtyp) {
        if (!"ftyp".equals(boxType) || box.length < boxHeader + 8) bad("MP4_FTYP_REQUIRED");
        sawFtyp = true;
      } else if (!"moov".equals(boxType)) {
        bad("MP4_MOOV_REQUIRED");
      }
      if ("moov".equals(boxType)) inspectInitialization(box, boxHeader, box.length, 0);
      initialization.write(box, 0, box.length);
      if ("moov".equals(boxType)) {
        byte[] init = initialization.toByteArray();
        initializationLength = init.length;
        sink.onInitialization(init);
        initialized = true;
        initialization.reset();
      }
      return;
    }
    if (moof == null) {
      if (!"moof".equals(boxType)) bad("MP4_MOOF_REQUIRED");
      moof = box;
      moofStart = boxStart;
      return;
    }
    if (!"mdat".equals(boxType)) bad("MP4_MDAT_REQUIRED");
    int used = moof.length + box.length;
    int padding = slotBytes - used;
    if (padding < 0) bad("MP4_SLOT_OVERFLOW");
    if (padding > 0 && padding < 8) bad("MP4_PADDING_TOO_SMALL");
    long outputStart = getInitializationLength() + (long) emittedSlots * slotBytes;
    rebaseAndValidate(moof, moofStart, outputStart - moofStart,
        boxStart + boxHeader, boxStart + box.length);
    byte[] slot = new byte[slotBytes];
    System.arraycopy(moof, 0, slot, 0, moof.length);
    System.arraycopy(box, 0, slot, moof.length, box.length);
    if (padding > 0) {
      put32(slot, used, padding);
      byte[] free = "free".getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(free, 0, slot, used + 4, free.length);
    }
    sink.onFragment(slot);
    emittedSlots++;
    Arrays.fill(moof, (byte) 0);
    moof = null;
    if (emittedSlots == slotCount) {
      sink.onComplete();
      complete = true;
    }
  }

  private long initializationLength;
  private long getInitializationLength() { return initializationLength; }

  private void inspectInitialization(byte[] data, int start, int end, int depth) throws IOException {
    if (depth > 16) bad("MP4_NESTING");
    for (Box child : children(data, start, end)) {
      rejectUnsupported(child.type);
      switch (child.type) {
        case "moov": case "trak": case "mdia": case "minf": case "stbl":
        case "mvex": case "edts": case "dinf": case "udta":
          inspectInitialization(data, child.body, child.end, depth + 1);
          break;
        case "meta":
          need(child.body + 4 <= child.end);
          inspectInitialization(data, child.body + 4, child.end, depth + 1);
          break;
        case "stco": case "co64":
          need(child.body + 8 <= child.end);
          if (u32(data, child.body + 4) != 0) bad("MP4_NONFRAGMENTED_OFFSETS");
          break;
        case "trex":
          need(child.body + 24 == child.end);
          long track = u32(data, child.body + 4);
          if (track == 0 || defaultSizes.size() >= 16 || defaultSizes.containsKey(track)) {
            bad("MP4_TRACK_INVALID");
          }
          defaultSizes.put(track, u32(data, child.body + 16));
          defaultDurations.put(track, u32(data, child.body + 12));
          break;
        case "stsd":
          need(child.body + 8 <= child.end);
          List<Box> entries = children(data, child.body + 8, child.end);
          if (u32(data, child.body + 4) != entries.size()) bad("MP4_SAMPLE_ENTRY_INVALID");
          for (Box entry : entries) {
            if (!"avc1".equals(entry.type) && !"avc3".equals(entry.type)) {
              bad("MP4_SAMPLE_ENTRY_UNSUPPORTED");
            }
            need(entry.body + 78 <= entry.end);
            inspectInitialization(data, entry.body + 78, entry.end, depth + 1);
          }
          break;
        case "dref":
          need(child.body + 8 <= child.end);
          List<Box> refs = children(data, child.body + 8, child.end);
          if (u32(data, child.body + 4) != refs.size()) bad("MP4_DATA_REFERENCE");
          for (Box ref : refs) {
            need(ref.body + 4 <= ref.end);
            if (!"url ".equals(ref.type) || u32(data, ref.body) != 1) bad("MP4_DATA_REFERENCE");
          }
          break;
        default:
          // Leaf codec/timing metadata has no file offsets and stays byte-for-byte unchanged.
          break;
      }
    }
  }

  private void rebaseAndValidate(byte[] data, long originalMoof, long delta,
      long mediaStart, long mediaEnd) throws IOException {
    Box root = boxAt(data, 0, data.length);
    boolean mfhd = false;
    int tracks = 0;
    int runs = 0;
    for (Box child : children(data, root.body, root.end)) {
      if ("mfhd".equals(child.type)) {
        need(!mfhd && child.body + 8 == child.end);
        mfhd = true;
      } else if ("traf".equals(child.type)) {
        tracks++;
        runs += validateTrack(data, child, originalMoof, delta, mediaStart, mediaEnd);
      } else bad("MP4_FRAGMENT_BOX_UNSUPPORTED");
    }
    if (!mfhd || tracks == 0 || tracks > 16 || runs == 0) bad("MP4_FRAGMENT_INVALID");
  }

  private int validateTrack(byte[] data, Box traf, long originalMoof, long delta,
      long mediaStart, long mediaEnd) throws IOException {
    List<Box> parts = children(data, traf.body, traf.end);
    Box tfhd = null;
    for (Box part : parts) {
      if ("tfhd".equals(part.type)) {
        if (tfhd != null) bad("MP4_TFHD_DUPLICATE");
        tfhd = part;
      }
    }
    if (tfhd == null) bad("MP4_TFHD_REQUIRED");
    need(tfhd.body + 8 <= tfhd.end);
    long flags = u32(data, tfhd.body);
    if ((flags & ~0x2003bL) != 0) bad("MP4_TFHD_FLAGS");
    long track = u32(data, tfhd.body + 4);
    if (track == 0) bad("MP4_TRACK_INVALID");
    int cursor = tfhd.body + 8;
    long base = originalMoof;
    if ((flags & 1) != 0) {
      need(cursor + 8 <= tfhd.end);
      base = positive64(data, cursor);
      long rebased;
      try { rebased = Math.addExact(base, delta); }
      catch (ArithmeticException overflow) { throw new IOException("MP4_OFFSET_OVERFLOW"); }
      if (rebased < 0) bad("MP4_OFFSET_OVERFLOW");
      put64(data, cursor, rebased);
      cursor += 8;
    } else if ((flags & 0x20000) == 0) bad("MP4_IMPLICIT_BASE_UNSUPPORTED");
    if ((flags & 2) != 0) cursor += 4;
    long duration = defaultDurations.containsKey(track) ? defaultDurations.get(track) : 0;
    if ((flags & 8) != 0) {
      need(cursor + 4 <= tfhd.end);
      duration = u32(data, cursor);
      cursor += 4;
    }
    long size = defaultSizes.containsKey(track) ? defaultSizes.get(track) : 0;
    if ((flags & 16) != 0) {
      need(cursor + 4 <= tfhd.end);
      size = u32(data, cursor);
      cursor += 4;
    }
    if ((flags & 32) != 0) cursor += 4;
    need(cursor == tfhd.end);
    long priorEnd = -1;
    int runs = 0;
    long explicitTime = -1;
    for (Box part : parts) {
      if ("tfdt".equals(part.type)) {
        if (explicitTime >= 0) bad("MP4_TFDT_DUPLICATE");
        need(part.body + 4 <= part.end);
        int version = data[part.body] & 255;
        need((u32(data, part.body) & 0xffffffL) == 0);
        need((version == 0 && part.body + 8 == part.end)
            || (version == 1 && part.body + 12 == part.end));
        explicitTime = version == 0 ? u32(data, part.body + 4) : positive64(data, part.body + 4);
      }
    }
    if (!decodeEnds.containsKey(track) && decodeEnds.size() >= 16) bad("MP4_TRACK_INVALID");
    long decodeStart = explicitTime >= 0 ? explicitTime :
        (decodeEnds.containsKey(track) ? decodeEnds.get(track) : 0);
    long totalDuration = 0;
    for (Box part : parts) {
      switch (part.type) {
        case "tfhd": break;
        case "tfdt": break;
        case "trun":
          need(part.body + 8 <= part.end);
          int versionRun = data[part.body] & 255;
          long runFlags = u32(data, part.body) & 0xffffffL;
          if (versionRun > 1 || (runFlags & ~0xf05L) != 0) bad("MP4_TRUN_FLAGS");
          long samples = u32(data, part.body + 4);
          if (samples == 0 || samples > slotBytes) bad("MP4_SAMPLE_COUNT");
          int position = part.body + 8;
          long from = priorEnd >= 0 ? priorEnd : base;
          if ((runFlags & 1) != 0) {
            need(position + 4 <= part.end);
            int relative = (int) u32(data, position);
            try { from = Math.addExact(base, relative); }
            catch (ArithmeticException overflow) { throw new IOException("MP4_OFFSET_OVERFLOW"); }
            position += 4;
          }
          if ((runFlags & 4) != 0) position += 4;
          long bytes = 0;
          for (long index = 0; index < samples; index++) {
            long sampleDuration = duration;
            if ((runFlags & 0x100) != 0) {
              need(position + 4 <= part.end);
              sampleDuration = u32(data, position);
              position += 4;
            }
            if (sampleDuration <= 0) bad("MP4_SAMPLE_DURATION");
            try { totalDuration = Math.addExact(totalDuration, sampleDuration); }
            catch (ArithmeticException overflow) { throw new IOException("MP4_TIMING_OVERFLOW"); }
            long sampleSize = size;
            if ((runFlags & 0x200) != 0) {
              need(position + 4 <= part.end);
              sampleSize = u32(data, position);
              position += 4;
            }
            if ((runFlags & 0x400) != 0) position += 4;
            if ((runFlags & 0x800) != 0) position += 4;
            if (sampleSize <= 0 || sampleSize > slotBytes) bad("MP4_SAMPLE_SIZE");
            bytes += sampleSize;
            if (bytes > slotBytes || position > part.end) bad("MP4_SAMPLE_BUDGET");
          }
          need(position == part.end);
          if (from < mediaStart || from > mediaEnd || bytes > mediaEnd - from) {
            bad("MP4_DATA_OFFSET_INVALID");
          }
          priorEnd = from + bytes;
          runs++;
          break;
        case "sdtp": break;
        default: bad("MP4_FRAGMENT_BOX_UNSUPPORTED");
      }
    }
    if (runs == 0) bad("MP4_FRAGMENT_TIMING_REQUIRED");
    // tfdt is optional: Media3 uses valid implicit continuation from the previous track fragment.
    // Resolve positive sample durations before accepting that continuation; never invent timestamps.
    try { decodeEnds.put(track, Math.addExact(decodeStart, totalDuration)); }
    catch (ArithmeticException overflow) { throw new IOException("MP4_TIMING_OVERFLOW"); }
    return runs;
  }

  private static void rejectUnsupported(String type) throws IOException {
    switch (type) {
      case "sidx": case "ssix": case "mfra": case "tfra": case "mfro":
      case "saio": case "saiz": case "senc": case "pssh": case "sinf":
      case "encv": case "enca": case "iloc": case "uuid":
        bad("MP4_OFFSET_OR_ENCRYPTION_UNSUPPORTED");
        break;
      default: break;
    }
  }

  private static List<Box> children(byte[] data, int start, int end) throws IOException {
    List<Box> result = new ArrayList<>();
    int position = start;
    while (position < end) {
      if (result.size() >= 8192) bad("MP4_BOX_COUNT");
      Box child = boxAt(data, position, end);
      result.add(child);
      position = child.end;
    }
    need(position == end);
    return result;
  }

  private static Box boxAt(byte[] data, int offset, int end) throws IOException {
    need(offset >= 0 && offset + 8 <= end && end <= data.length);
    long size = u32(data, offset);
    int header = 8;
    if (size == 1) {
      need(offset + 16 <= end);
      size = positive64(data, offset + 8);
      header = 16;
    }
    need(size >= header && size <= end - offset);
    return new Box(type(data, offset + 4), offset + header, offset + (int) size);
  }

  private static final class Box {
    final String type;
    final int body;
    final int end;
    Box(String type, int body, int end) { this.type = type; this.body = body; this.end = end; }
  }

  private static String type(byte[] bytes, int offset) {
    return new String(bytes, offset, 4, StandardCharsets.ISO_8859_1);
  }
  private static long u32(byte[] bytes, int offset) {
    return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
        | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
  }
  private static long positive64(byte[] bytes, int offset) throws IOException {
    if ((bytes[offset] & 128) != 0) bad("MP4_OFFSET_OVERFLOW");
    return (u32(bytes, offset) << 32) | u32(bytes, offset + 4);
  }
  private static void put32(byte[] bytes, int offset, long value) {
    for (int i = 3; i >= 0; i--) { bytes[offset + i] = (byte) value; value >>>= 8; }
  }
  private static void put64(byte[] bytes, int offset, long value) {
    for (int i = 7; i >= 0; i--) { bytes[offset + i] = (byte) value; value >>>= 8; }
  }
  private static void need(boolean condition) throws IOException { if (!condition) bad("MP4_INVALID"); }
  private static void bad(String code) throws IOException { throw new IOException(code); }

  @Override public synchronized void close() throws IOException {
    if (!open) return;
    open = false;
    if (box != null) Arrays.fill(box, (byte) 0);
    if (moof != null) Arrays.fill(moof, (byte) 0);
    Arrays.fill(header, (byte) 0);
    box = null;
    moof = null;
    initialization.reset();
    defaultSizes.clear();
    defaultDurations.clear();
    decodeEnds.clear();
    if (!complete) throw new IOException("MP4_INCOMPLETE");
  }
}
