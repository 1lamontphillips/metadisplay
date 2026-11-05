package com.lamontphillips.metadisplay.transporttest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Standalone structural/media-buffer tests. Payloads are synthetic; no camera data is used. */
public final class FixedBudgetMediaTest {
  private static final int SLOT = 256;
  private static final int DEFAULT_BASE_IS_MOOF = 0x020000;
  private static final byte[] SAMPLES = "sidxencvsaio".getBytes(StandardCharsets.US_ASCII);

  private FixedBudgetMediaTest() {}

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    tests.add(test("live experiment uses exact finite budget", FixedBudgetMediaTest::configuredBudget));
    tests.add(test("initialization emits once after complete ftyp and moov", FixedBudgetMediaTest::incrementalInitialization));
    tests.add(test("one-byte writes preserve samples timestamps and relative offsets", FixedBudgetMediaTest::incrementalFragments));
    tests.add(test("absolute tfhd bases rebase by actual preceding padding", () -> absoluteRebasing(false)));
    tests.add(test("absolute tfhd base inside mdat rebases correctly", () -> absoluteRebasing(true)));
    tests.add(test("omitted tfdt continues explicit sample durations without rewriting media", FixedBudgetMediaTest::implicitDecodeContinuation));
    tests.add(test("omitted tfdt resolves positive tfhd and trex default durations", FixedBudgetMediaTest::implicitDefaultDurations));
    tests.add(test("omitted tfdt rejects missing and zero sample durations", FixedBudgetMediaTest::implicitInvalidDurations));
    tests.add(test("exact-size fragment needs no padding box", () -> paddingSize(0)));
    tests.add(test("eight-byte padding is a valid empty free box", () -> paddingSize(8)));
    tests.add(test("larger padding is a top-level free box", () -> paddingSize(37)));
    for (int remaining = 1; remaining <= 7; remaining++) {
      final int gap = remaining;
      tests.add(test("reject unrepresentable padding of " + gap + " bytes", () -> invalidPadding(gap)));
    }
    tests.add(test("fragment overflow is rejected without emitting a slot", FixedBudgetMediaTest::fragmentOverflow));
    tests.add(test("quota discards trailing bytes without extra emissions", FixedBudgetMediaTest::quotaDiscards));
    tests.add(test("incomplete close throws and closes channel", FixedBudgetMediaTest::incompleteClose));
    tests.add(test("channel honors ByteBuffer position and limit", FixedBudgetMediaTest::bufferPositions));
    tests.add(test("extended-size initialization box remains bounded and intact", FixedBudgetMediaTest::extendedInitialization));
    tests.add(test("malformed and huge box headers fail before allocation", FixedBudgetMediaTest::malformedHeaders));
    tests.add(test("fragment ordering is enforced", FixedBudgetMediaTest::invalidOrdering));
    tests.add(test("trun cannot address bytes outside mdat", FixedBudgetMediaTest::invalidDataOffset));
    tests.add(test("trun sample lengths cannot overrun mdat", FixedBudgetMediaTest::invalidSampleSize));
    tests.add(test("unknown tfhd flags are rejected", () -> unsupportedTfhdFlags(0x400000)));
    tests.add(test("duration-is-empty tfhd is rejected", () -> unsupportedTfhdFlags(0x010000)));
    tests.add(test("absolute offset overflow cannot emit another slot", FixedBudgetMediaTest::absoluteOverflow));
    for (String type : new String[] {"sidx", "mfra", "tfra", "saio", "saiz", "senc", "pssh", "encv", "enca", "iloc"}) {
      tests.add(test("reject unsupported index or encrypted box " + type, () -> unsupportedBox(type)));
    }
    tests.add(test("empty stco and co64 initialization tables are accepted", FixedBudgetMediaTest::emptyOffsetTables));
    tests.add(test("nonempty stco initialization table is rejected", () -> nonemptyOffsetTable("stco")));
    tests.add(test("nonempty co64 initialization table is rejected", () -> nonemptyOffsetTable("co64")));
    tests.add(test("sink failures propagate as IOException", FixedBudgetMediaTest::sinkFailures));
    tests.add(test("parser constructor enforces bounded allocation", FixedBudgetMediaTest::constructorBounds));
    tests.add(test("buffer initially has no advertised response", FixedBudgetMediaTest::emptyBuffer));
    tests.add(test("buffer advertises init plus exact slot budget", FixedBudgetMediaTest::bufferBudget));
    tests.add(test("buffer random access spans initialization and slot boundaries", FixedBudgetMediaTest::bufferRandomAccess));
    tests.add(test("buffer read times out while future bytes are unavailable", FixedBudgetMediaTest::bufferReadTimeout));
    tests.add(test("buffer completion retains bytes and ends reads", FixedBudgetMediaTest::bufferCompletion));
    tests.add(test("initialization wakes a waiting initializer", FixedBudgetMediaTest::initializationWake));
    tests.add(test("fragment publication wakes a waiting reader", FixedBudgetMediaTest::fragmentWake));
    tests.add(test("buffer failure wakes blocked reads with IOException", FixedBudgetMediaTest::failureWake));
    tests.add(test("buffer close wakes initialization and removes read access", FixedBudgetMediaTest::closeWake));
    tests.add(test("buffer rejects duplicate init and invalid slot publication", FixedBudgetMediaTest::bufferRejectsInvalidPublication));
    tests.add(test("fixture init is immediate and slot n waits for n times cadence", FixedBudgetMediaTest::producerPacing));
    tests.add(test("fixture producer stops at quota and retains cache", FixedBudgetMediaTest::producerQuota));
    tests.add(test("short fixture reports a fixed safe failure", FixedBudgetMediaTest::producerUnderflow));
    tests.add(test("overflow fixture reports failure without truncating a slot", FixedBudgetMediaTest::producerOverflow));
    tests.add(test("producer close interrupts pacing without a false COMPLETE", FixedBudgetMediaTest::producerCancellation));

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
    System.out.println("FixedBudgetMediaTest: " + (tests.size() - failures) + "/"
        + tests.size() + " passed; " + failures + " failed");
    if (failures != 0) System.exit(1);
  }

  private static void configuredBudget() throws Exception {
    equal(20, LiveVideoConfig.FRAGMENTS, "finite slot count");
    equal(65536, LiveVideoConfig.SLOT_BYTES, "fixed slot bytes");
    byte[] init = initialization();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(LiveVideoConfig.FRAGMENTS, LiveVideoConfig.SLOT_BYTES)) {
      buffer.onInitialization(init);
      equal((long) init.length + 20L * 65536, buffer.getContentLength(), "honest fixed Content-Length");
      equal(init.length, buffer.getProducedBytes(), "advertising a budget does not fabricate produced bytes");
      check(!buffer.isComplete(), "the planned response is not already complete");
    }
  }

  private static void incrementalInitialization() throws Exception {
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(1, SLOT, sink);
    try {
      byte[] ftyp = ftyp(false);
      byte[] moov = moov();
      writeChunks(parser, ftyp, 1);
      equal(0, sink.initializations.size(), "ftyp alone does not emit initialization");
      writeChunks(parser, Arrays.copyOf(moov, moov.length - 1), 3);
      equal(0, sink.initializations.size(), "partial moov does not emit initialization");
      writeChunks(parser, Arrays.copyOfRange(moov, moov.length - 1, moov.length), 1);
      equal(1, sink.initializations.size(), "initialization emits once");
      equalBytes(concat(ftyp, moov), sink.initializations.get(0), "unmodified initialization");
      equal(0, sink.slots.size(), "initialization is not a media slot");
      check(!parser.isComplete(), "initialization does not complete the slot quota");
      writeChunks(parser, fragment(false, false, ftyp.length + moov.length, 1, 0), 5);
      parser.close();
      equal(1, sink.initializations.size(), "no repeated initialization");
    } finally {
      closeQuietly(parser);
    }
  }

  private static void incrementalFragments() throws Exception {
    byte[] init = initialization();
    byte[] first = fragment(false, false, init.length, 1, 5_000_000_123L);
    byte[] second = fragment(false, false, init.length + first.length, 2, 5_000_001_147L);
    RecordingSink sink = parse(2, SLOT, concat(init, first, second), 1);
    equal(2, sink.slots.size(), "two complete slots");
    equal(1, sink.completions, "quota completes exactly once");
    for (int index = 0; index < 2; index++) {
      byte[] raw = index == 0 ? first : second;
      byte[] slot = sink.slots.get(index);
      equal(SLOT, slot.length, "fixed slot size");
      equalBytes(raw, Arrays.copyOf(slot, raw.length), "default-base-is-moof fragment stays byte exact");
      assertSlot(slot, raw.length);
      Box traf = child(slot, top(slot, "moof"), "traf");
      Box tfhd = child(slot, traf, "tfhd");
      equal(DEFAULT_BASE_IS_MOOF, intAt(slot, tfhd.start + 8) & 0x00ffffff, "relative tfhd flags retained");
      Box tfdt = child(slot, traf, "tfdt");
      equal(index == 0 ? 5_000_000_123L : 5_000_001_147L, longAt(slot, tfdt.start + 12), "64-bit decode timestamp retained");
      Box trun = child(slot, traf, "trun");
      equal(top(slot, "moof").size + 8, intAt(slot, trun.start + 16), "trun relative data offset retained");
      equal(512, intAt(slot, trun.start + 20), "sample duration retained");
      equal(5, intAt(slot, trun.start + 24), "first sample length retained");
      equal(7, intAt(slot, trun.start + 36), "second sample length retained");
      equalBytes(SAMPLES, payload(slot, top(slot, "mdat")), "sample bytes are not parsed as box names");
    }
  }

  private static void absoluteRebasing(boolean baseInMdat) throws Exception {
    byte[] init = initialization();
    byte[] first = fragment(true, baseInMdat, init.length, 1, 0);
    byte[] second = fragment(true, baseInMdat, init.length + first.length, 2, 1024);
    byte[] third = fragment(true, baseInMdat, init.length + first.length + second.length, 3, 2048);
    byte[][] originals = {first, second, third};
    RecordingSink sink = parse(3, SLOT, concat(init, first, second, third), 7);
    long rawStart = init.length;
    for (int index = 0; index < originals.length; index++) {
      byte[] original = originals[index];
      byte[] expected = original.clone();
      Box tfhd = child(original, child(original, top(original, "moof"), "traf"), "tfhd");
      long delta = init.length + (long) index * SLOT - rawStart;
      putLong(expected, tfhd.start + 16, longAt(original, tfhd.start + 16) + delta);
      equalBytes(expected, Arrays.copyOf(sink.slots.get(index), original.length),
          "only the absolute tfhd base changes by actual accumulated padding");
      assertSlot(sink.slots.get(index), original.length);
      rawStart += original.length;
    }
  }

  private static void implicitDecodeContinuation() throws Exception {
    byte[] init = initialization();
    byte[] first = fragmentWithoutTfdt(true, init.length, 1, 512, null);
    byte[] second = fragmentWithoutTfdt(true, init.length + first.length, 2, 512, null);
    RecordingSink sink = parse(2, SLOT, concat(init, first, second), 3);
    byte[][] originals = {first, second};
    for (int index = 0; index < originals.length; index++) {
      byte[] original = originals[index];
      byte[] expected = original.clone();
      Box traf = child(original, top(original, "moof"), "traf");
      Box tfhd = child(original, traf, "tfhd");
      long delta = index == 0 ? 0 : SLOT - first.length;
      putLong(expected, tfhd.start + 16, longAt(original, tfhd.start + 16) + delta);
      byte[] slot = sink.slots.get(index);
      equalBytes(expected, Arrays.copyOf(slot, original.length), "implicit timing only rebases the absolute base offset");
      for (Box part : boxes(slot, traf.start + traf.header, traf.start + traf.size)) {
        check(!part.type.equals("tfdt"), "parser must not synthesize a decode-time box");
      }
      Box trun = child(slot, traf, "trun");
      equal(512, intAt(slot, trun.start + 20), "explicit first sample duration remains intact");
      equal(512, intAt(slot, trun.start + 32), "explicit second sample duration remains intact");
      equalBytes(SAMPLES, payload(slot, top(slot, "mdat")), "implicit continuation preserves all sample bytes");
      assertSlot(slot, original.length);
    }

    // A second fragment must continue the prior end rather than silently restarting at zero.
    byte[] nearLimit = fragment(true, false, init.length, 1, Long.MAX_VALUE - 2047);
    byte[] overflow = fragmentWithoutTfdt(true, init.length + nearLimit.length, 2, 512, null);
    RecordingSink overflowSink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(2, SLOT, overflowSink);
    try {
      writeChunks(parser, concat(init, nearLimit), 7);
      equal(1, overflowSink.slots.size(), "valid explicit decode time publishes the first slot");
      expectIOException(() -> writeChunks(parser, overflow, 3));
      equal(1, overflowSink.slots.size(), "implicit decode-time overflow publishes no additional slot");
      equal(0, overflowSink.completions, "timing overflow cannot complete the quota");
    } finally { closeQuietly(parser); }
  }

  private static void implicitDefaultDurations() throws Exception {
    for (boolean useTfhd : new boolean[] {true, false}) {
      // A zero trex default makes acceptance depend on the positive tfhd override.
      byte[] init = initializationWithDefaultDuration(useTfhd ? 0 : 512);
      Integer tfhdDuration = useTfhd ? 700 : null;
      byte[] first = fragmentWithoutTfdt(false, init.length, 1, null, tfhdDuration);
      byte[] second = fragmentWithoutTfdt(false, init.length + first.length, 2, null, tfhdDuration);
      RecordingSink sink = parse(2, SLOT, concat(init, first, second), 5);
      equalBytes(init, sink.initializations.get(0), "duration defaults leave initialization unchanged");
      for (int index = 0; index < 2; index++) {
        byte[] raw = index == 0 ? first : second;
        byte[] slot = sink.slots.get(index);
        equalBytes(raw, Arrays.copyOf(slot, raw.length), "resolved default durations do not rewrite the fragment");
        Box trun = child(slot, child(slot, top(slot, "moof"), "traf"), "trun");
        equal(0, intAt(slot, trun.start + 8) & 0x100, "sample durations remain omitted from trun");
        equalBytes(SAMPLES, payload(slot, top(slot, "mdat")), "default timing leaves sample bytes intact");
        assertSlot(slot, raw.length);
      }
    }
  }

  private static void implicitInvalidDurations() throws Exception {
    Integer[][] durations = {
        {null, null, null}, // No trex, tfhd or per-sample duration.
        {0, null, null},    // A zero trex default cannot establish continuation.
        {512, 0, null},    // An explicit zero tfhd default overrides a valid trex default.
        {512, 512, 0}      // An explicit zero sample duration overrides both positive defaults.
    };
    for (Integer[] values : durations) {
      byte[] init = initializationWithDefaultDuration(values[0]);
      byte[] raw = fragmentWithoutTfdt(false, init.length, 1, values[2], values[1]);
      rejectParser(concat(init, raw), 1, SLOT);
    }
  }

  private static void paddingSize(int remaining) throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    RecordingSink sink = parse(1, raw.length + remaining, concat(init, raw), 11);
    byte[] slot = sink.slots.get(0);
    equal(raw.length + remaining, slot.length, "slot exact size");
    equalBytes(raw, Arrays.copyOf(slot, raw.length), "padding never extends or rewrites mdat");
    assertSlot(slot, raw.length);
    if (remaining > 0) {
      Box free = top(slot, "free");
      equal(remaining, free.size, "free consumes exactly remaining bytes");
      for (byte value : payload(slot, free)) equal(0, value, "padding payload is zero initialized");
    }
  }

  private static void invalidPadding(int remaining) throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(1, raw.length + remaining, sink);
    try {
      expectIOException(() -> writeChunks(parser, concat(init, raw), 13));
      equal(0, sink.slots.size(), "unrepresentable free box cannot emit a slot");
      equal(0, sink.completions, "invalid padding cannot report complete");
    } finally { closeQuietly(parser); }
  }

  private static void fragmentOverflow() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(1, raw.length - 1, sink);
    try {
      expectIOException(() -> writeChunks(parser, concat(init, raw), 9));
      equal(0, sink.slots.size(), "overflow cannot truncate or publish sample bytes");
      check(!parser.isComplete(), "overflow cannot report quota completion");
    } finally { closeQuietly(parser); }
  }

  private static void quotaDiscards() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    byte[] ignored = concat(new byte[] {0, 1, 2, 3, 4}, init, raw);
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(1, SLOT, sink);
    try {
      writeChunks(parser, concat(init, raw, ignored), 1);
      check(parser.isComplete(), "quota reached");
      equal(1, parser.getEmittedSlots(), "no extra slot emission");
      equal(1, sink.initializations.size(), "discard cannot restart initialization");
      equal(1, sink.slots.size(), "discard cannot emit another slot");
      equal(1, sink.completions, "completion callback is one-shot");
      equal(init.length + raw.length + ignored.length, parser.getRawBytes(), "raw counter includes discarded bytes");
      parser.close();
      parser.close();
      check(!parser.isOpen(), "complete close is idempotent");
      expectIOException(() -> parser.write(ByteBuffer.wrap(new byte[] {1})));
    } finally { closeQuietly(parser); }
  }

  private static void incompleteClose() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    for (byte[] prefix : new byte[][] {new byte[0], Arrays.copyOf(init, init.length - 1), init,
        concat(init, Arrays.copyOf(raw, raw.length - 1)), concat(init, raw)}) {
      FixedBudgetMp4 parser = new FixedBudgetMp4(2, SLOT, new RecordingSink());
      try {
        writeChunks(parser, prefix, 3);
        expectIOException(parser::close);
        check(!parser.isOpen(), "failed incomplete close still releases the channel");
      } finally { closeQuietly(parser); }
    }
  }

  private static void bufferPositions() throws Exception {
    byte[] init = initialization();
    byte[] bytes = concat(init, fragment(false, false, init.length, 1, 0));
    byte[] framed = concat(new byte[] {99, 98, 97}, bytes, new byte[] {96, 95});
    ByteBuffer input = ByteBuffer.wrap(framed).asReadOnlyBuffer();
    input.position(3);
    input.limit(3 + bytes.length);
    RecordingSink sink = new RecordingSink();
    try (FixedBudgetMp4 parser = new FixedBudgetMp4(1, SLOT, sink)) {
      equal(bytes.length, parser.write(input), "write consumes only remaining input");
      equal(input.limit(), input.position(), "write advances position to limit");
      equal(bytes.length, parser.getRawBytes(), "raw count excludes bytes outside buffer window");
      equal(0, parser.write(ByteBuffer.allocate(0)), "empty write consumes nothing");
      equalBytes(init, sink.initializations.get(0), "window excludes prefix bytes");
    }
  }

  private static void extendedInitialization() throws Exception {
    byte[] init = concat(ftyp(true), moov());
    byte[] raw = fragment(false, false, init.length, 1, 0);
    RecordingSink sink = parse(1, SLOT, concat(init, raw), 1);
    equalBytes(init, sink.initializations.get(0), "extended box header preserved");
  }

  private static void malformedHeaders() throws Exception {
    byte[][] malformed = {
      concat(intBytes(0), ascii("ftyp")),
      concat(intBytes(7), ascii("ftyp")),
      concat(intBytes(Integer.MAX_VALUE), ascii("ftyp")),
      concat(intBytes(1), ascii("ftyp"), longBytes(Long.MAX_VALUE)),
      concat(intBytes(1), ascii("ftyp"), longBytes(15))
    };
    for (byte[] bytes : malformed) rejectParser(bytes, 1, SLOT);
  }

  private static void invalidOrdering() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    byte[] moof = Arrays.copyOf(raw, top(raw, "moof").size);
    rejectParser(moov(), 1, SLOT);
    rejectParser(concat(ftyp(false), ftyp(false)), 1, SLOT);
    rejectParser(concat(init, box("mdat", SAMPLES)), 1, SLOT);
    rejectParser(concat(init, moof, moof), 1, SLOT);
    rejectParser(concat(init, box("sidx", new byte[24])), 1, SLOT);
  }

  private static void invalidDataOffset() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    Box trun = child(raw, child(raw, top(raw, "moof"), "traf"), "trun");
    putInt(raw, trun.start + 16, 1_000_000);
    rejectParser(concat(init, raw), 1, SLOT);
  }

  private static void invalidSampleSize() throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    Box trun = child(raw, child(raw, top(raw, "moof"), "traf"), "trun");
    putInt(raw, trun.start + 24, 1_000_000);
    rejectParser(concat(init, raw), 1, SLOT);
  }

  private static void unsupportedTfhdFlags(int unsupported) throws Exception {
    byte[] init = initialization();
    byte[] raw = fragment(false, false, init.length, 1, 0);
    Box tfhd = child(raw, child(raw, top(raw, "moof"), "traf"), "tfhd");
    putInt(raw, tfhd.start + 8, DEFAULT_BASE_IS_MOOF | unsupported);
    rejectParser(concat(init, raw), 1, SLOT);
  }

  private static void absoluteOverflow() throws Exception {
    byte[] init = initialization();
    byte[] first = fragment(true, false, init.length, 1, 0);
    byte[] second = fragment(true, false, init.length + first.length, 2, 1024);
    Box tfhd = child(second, child(second, top(second, "moof"), "traf"), "tfhd");
    putLong(second, tfhd.start + 16, Long.MAX_VALUE);
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(2, SLOT, sink);
    try {
      expectIOException(() -> writeChunks(parser, concat(init, first, second), 17));
      equal(1, sink.slots.size(), "overflow cannot publish the second slot");
      equal(0, sink.completions, "overflow cannot mark complete");
    } finally { closeQuietly(parser); }
  }

  private static void unsupportedBox(String type) throws Exception {
    byte[] init = concat(ftyp(false), box("moov", concat(mvex(), box(type, new byte[24]))));
    rejectParser(init, 1, SLOT);
  }

  private static void emptyOffsetTables() throws Exception {
    byte[] tables = concat(fullBox("stco", 0, concat(intBytes(0))), fullBox("co64", 0, concat(intBytes(0))));
    byte[] init = concat(ftyp(false), box("moov", concat(mvex(), box("trak",
        box("mdia", box("minf", box("stbl", tables)))))));
    byte[] raw = fragment(false, false, init.length, 1, 0);
    RecordingSink sink = parse(1, SLOT, concat(init, raw), 2);
    equalBytes(init, sink.initializations.get(0), "empty offset tables remain intact");
  }

  private static void nonemptyOffsetTable(String type) throws Exception {
    byte[] offsets = type.equals("stco") ? intBytes(16) : longBytes(16);
    byte[] table = fullBox(type, 0, concat(intBytes(1), offsets));
    byte[] init = concat(ftyp(false), box("moov", concat(mvex(), box("trak",
        box("mdia", box("minf", box("stbl", table)))))));
    rejectParser(init, 1, SLOT);
  }

  private static void sinkFailures() throws Exception {
    byte[] init = initialization();
    byte[] bytes = concat(init, fragment(false, false, init.length, 1, 0));
    for (int failureStage = 0; failureStage < 3; failureStage++) {
      final int selected = failureStage;
      FixedBudgetMp4.Sink sink = new FixedBudgetMp4.Sink() {
        @Override public void onInitialization(byte[] ignored) throws IOException { if (selected == 0) throw new IOException("TEST_SINK"); }
        @Override public void onFragment(byte[] ignored) throws IOException { if (selected == 1) throw new IOException("TEST_SINK"); }
        @Override public void onComplete() throws IOException { if (selected == 2) throw new IOException("TEST_SINK"); }
      };
      FixedBudgetMp4 parser = new FixedBudgetMp4(1, SLOT, sink);
      try { expectIOException(() -> writeChunks(parser, bytes, 7)); }
      finally { closeQuietly(parser); }
    }
  }

  private static void constructorBounds() throws Exception {
    for (int[] limits : new int[][] {{0, SLOT}, {-1, SLOT}, {1, 15}, {1, 1024 * 1024 + 1},
        {Integer.MAX_VALUE, 1024 * 1024}, {33, 1024 * 1024}}) {
      expectIllegalArgument(() -> new FixedBudgetMp4(limits[0], limits[1], new RecordingSink()));
      expectIllegalArgument(() -> new BoundedMediaBuffer(limits[0], limits[1]));
    }
  }

  private static void emptyBuffer() throws Exception {
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(2, SLOT)) {
      equal(0, buffer.getContentLength(), "no Content-Length before initialization");
      equal(0, buffer.getProducedBytes(), "no invented bytes");
      equal(0, buffer.getCompletedSlots(), "no slots");
      check(!buffer.isComplete() && !buffer.isClosed() && buffer.getFailure() == null, "clean initial state");
      check(!buffer.awaitInitialized(35), "initialization timeout returns false");
      equal(0, buffer.readAt(0, new byte[1], 0, 0, 0), "zero-length read returns zero");
    }
  }

  private static void bufferBudget() throws Exception {
    byte[] init = initialization();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(3, SLOT)) {
      buffer.onInitialization(init);
      check(buffer.awaitInitialized(0), "initialization is immediately available");
      equal(init.length + 3L * SLOT, buffer.getContentLength(), "fixed advertised budget");
      buffer.onFragment(slotBytes(1));
      equal(init.length + SLOT, buffer.getProducedBytes(), "only published bytes are produced");
      equal(1, buffer.getCompletedSlots(), "one complete slot");
      equal(init.length + 3L * SLOT, buffer.getContentLength(), "advertised length never follows partial production");
      check(!buffer.isComplete(), "partial production is not completion");
    }
  }

  private static void bufferRandomAccess() throws Exception {
    byte[] init = initialization();
    byte[] first = slotBytes(1);
    byte[] second = slotBytes(2);
    byte[] expected = concat(init, first, second);
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(2, SLOT)) {
      buffer.onInitialization(init);
      buffer.onFragment(first);
      buffer.onFragment(second);
      buffer.onComplete();
      equalBytes(expected, readFully(buffer, 0, expected.length, 100), "full bounded response");
      equalBytes(Arrays.copyOfRange(expected, init.length - 3, init.length + SLOT + 9),
          readFully(buffer, init.length - 3, SLOT + 12, 100), "read spans initialization and both slots");
      byte[] target = new byte[13];
      Arrays.fill(target, (byte) 77);
      int count = buffer.readAt(init.length + 19, target, 3, 7, 100);
      check(count > 0 && count <= 7, "readAt returns bounded positive progress");
      equalBytes(Arrays.copyOfRange(first, 19, 19 + count), Arrays.copyOfRange(target, 3, 3 + count), "target offset respected");
      equal(77, target[0], "target prefix is unchanged");
      equal(77, target[12], "target suffix is unchanged");
    }
  }

  private static void bufferReadTimeout() throws Exception {
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(2, SLOT)) {
      byte[] init = initialization();
      buffer.onInitialization(init);
      long started = System.nanoTime();
      expectTimeout(() -> buffer.readAt(init.length, new byte[1], 0, 1, 55));
      check(elapsed(started) < 700, "unavailable data has a bounded wait");
      expectTimeout(() -> buffer.readAt(buffer.getContentLength(), new byte[1], 0, 1, 35));
      check(!buffer.isComplete(), "read timeout cannot invent EOF completion");
    }
  }

  private static void bufferCompletion() throws Exception {
    byte[] init = initialization();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(init);
      buffer.onFragment(slotBytes(3));
      check(!buffer.isComplete(), "quota publication awaits completion signal");
      buffer.onComplete();
      check(buffer.isComplete(), "explicit complete state");
      equal(-1, buffer.readAt(buffer.getContentLength(), new byte[1], 0, 1, 0), "completed response ends at exact advertised length");
      equalBytes(init, readFully(buffer, 0, init.length, 0), "normal completion retains initialization bytes");
      equalBytes(slotBytes(3), readFully(buffer, init.length, SLOT, 0), "normal completion retains media cache");
    }
  }

  private static void initializationWake() throws Exception {
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT);
        Worker<Boolean> waiter = new Worker<>(() -> buffer.awaitInitialized(3000))) {
      Thread.sleep(35);
      check(!waiter.isDone(), "awaitInitialized waits before publication");
      buffer.onInitialization(initialization());
      check(waiter.get(700), "initialization publication wakes waiter");
    }
  }

  private static void fragmentWake() throws Exception {
    byte[] init = initialization();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(init);
      try (Worker<Integer> waiter = new Worker<>(() -> buffer.readAt(init.length, new byte[1], 0, 1, 3000))) {
        Thread.sleep(35);
        check(!waiter.isDone(), "reader waits for unpublished slot");
        buffer.onFragment(slotBytes(5));
        equal(1, waiter.get(700), "slot publication wakes reader");
      }
    }
  }

  private static void failureWake() throws Exception {
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT);
        Worker<Integer> reader = new Worker<>(() -> buffer.readAt(0, new byte[1], 0, 1, 3000))) {
      Thread.sleep(35);
      buffer.fail("TEST_FAILURE");
      expectIOException(() -> reader.get(700));
      equal("TEST_FAILURE", buffer.getFailure(), "fixed failure code is retained");
      check(!buffer.isComplete(), "failure never reports complete");
      expectIOException(() -> buffer.awaitInitialized(0));
    }
  }

  private static void closeWake() throws Exception {
    BoundedMediaBuffer empty = new BoundedMediaBuffer(1, SLOT);
    try (Worker<Boolean> waiter = new Worker<>(() -> empty.awaitInitialized(3000))) {
      Thread.sleep(35);
      empty.close();
      expectIOException(() -> waiter.get(700));
    } finally { empty.close(); }
    BoundedMediaBuffer populated = new BoundedMediaBuffer(1, SLOT);
    try {
      populated.onInitialization(initialization());
      populated.onFragment(slotBytes(8));
      check(readFully(populated, 0, 1, 0).length == 1, "cache is readable before close");
      populated.close();
      populated.close();
      check(populated.isClosed(), "close is idempotent");
      expectIOException(() -> populated.readAt(0, new byte[1], 0, 1, 0));
    } finally { populated.close(); }
  }

  private static void bufferRejectsInvalidPublication() throws Exception {
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      expectIOException(() -> buffer.onFragment(slotBytes(1)));
    }
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(initialization());
      expectIOException(() -> buffer.onInitialization(initialization()));
    }
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(initialization());
      expectIOException(() -> buffer.onFragment(new byte[SLOT - 1]));
    }
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(initialization());
      expectIOException(buffer::onComplete);
    }
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(1, SLOT)) {
      buffer.onInitialization(initialization());
      buffer.onFragment(slotBytes(1));
      expectIOException(() -> buffer.onFragment(slotBytes(2)));
      equal(1, buffer.getCompletedSlots(), "overrun never grows cache beyond quota");
    }
  }

  private static void producerPacing() throws Exception {
    int count = 3;
    long cadence = 250;
    byte[] fixture = fixture(count);
    byte[] init = initialization();
    List<String> statuses = new CopyOnWriteArrayList<>();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(count, SLOT);
        PaddedFixtureProducer producer = new PaddedFixtureProducer(fixture, buffer, count, SLOT, cadence, statuses::add)) {
      long started = System.nanoTime();
      producer.start();
      check(buffer.awaitInitialized(200), "initialization is available before the first slot cadence");
      equalBytes(init, readFully(buffer, 0, init.length, 0), "fixture initialization is immediate and exact");
      for (int number = 1; number <= count; number++) {
        byte[] slot = readFully(buffer, init.length + (long) (number - 1) * SLOT, SLOT, 2000);
        check(elapsed(started) >= number * cadence - 45, "slot " + number + " must wait for its one-based cadence");
        assertSlot(slot, top(slot, "moof").size + top(slot, "mdat").size);
      }
      await(() -> buffer.isComplete() && statuses.contains("COMPLETE"), 1200, "producer completion after quota");
      equal(init.length + (long) count * SLOT, buffer.getProducedBytes(), "all planned bytes produced");
      equal(buffer.getContentLength(), buffer.getProducedBytes(), "complete producer meets honest Content-Length");
      check(buffer.getFailure() == null, "successful producer has no failure");
      equal(1, occurrences(statuses, "STARTED"), "one STARTED status");
      equal(1, occurrences(statuses, "COMPLETE"), "one COMPLETE status");
      equal(0, occurrences(statuses, "FAILED"), "no false failure");
      safeStatuses(statuses);
    }
  }

  private static void producerQuota() throws Exception {
    List<String> statuses = new CopyOnWriteArrayList<>();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(2, SLOT)) {
      PaddedFixtureProducer producer = new PaddedFixtureProducer(fixture(4), buffer, 2, SLOT, 40, statuses::add);
      try {
        producer.start();
        await(() -> buffer.isComplete() && statuses.contains("COMPLETE"), 1800, "fixture quota completion");
        equal(2, buffer.getCompletedSlots(), "extra fixture fragments are not emitted");
        equal(initialization().length + 2L * SLOT, buffer.getProducedBytes(), "fixed cache bound after quota");
        producer.close();
        producer.close();
        check(!buffer.isClosed(), "producer completion does not close its consumer-owned buffer");
        equalBytes(initialization(), readFully(buffer, 0, initialization().length, 0), "cache retained after producer close");
        equal(1, occurrences(statuses, "COMPLETE"), "quota COMPLETE emitted once");
      } finally { producer.close(); }
    }
  }

  private static void producerUnderflow() throws Exception {
    producerFailure(fixture(1), 2, SLOT);
  }

  private static void producerOverflow() throws Exception {
    producerFailure(fixture(1), 1, 64);
  }

  private static void producerFailure(byte[] fixture, int count, int slotBytes) throws Exception {
    List<String> statuses = new CopyOnWriteArrayList<>();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(count, slotBytes);
        PaddedFixtureProducer producer = new PaddedFixtureProducer(fixture, buffer, count, slotBytes, 35, statuses::add)) {
      producer.start();
      await(() -> statuses.contains("FAILED"), 2000, "failed fixture must report failure");
      equal("FIXTURE_FAILED", buffer.getFailure(), "producer stores a fixed safe failure code");
      check(!buffer.isComplete(), "producer failure cannot report complete");
      equal(0, occurrences(statuses, "COMPLETE"), "failed producer emits no COMPLETE");
      check(buffer.getProducedBytes() <= initialization().length + (long) count * slotBytes, "failure cannot exceed bounded cache");
      if (slotBytes == 64) equal(0, buffer.getCompletedSlots(), "overflow publishes no truncated slot");
      safeStatuses(statuses);
    }
  }

  private static void producerCancellation() throws Exception {
    List<String> statuses = new CopyOnWriteArrayList<>();
    try (BoundedMediaBuffer buffer = new BoundedMediaBuffer(3, SLOT)) {
      PaddedFixtureProducer producer = new PaddedFixtureProducer(fixture(3), buffer, 3, SLOT, 2500, statuses::add);
      try {
        producer.start();
        check(buffer.awaitInitialized(500), "init is available while pacing waits");
        equal(0, buffer.getCompletedSlots(), "no slot before first cadence");
        long stoppedAt = System.nanoTime();
        producer.close();
        producer.close();
        check(elapsed(stoppedAt) < 1000, "close interrupts pacing instead of waiting for its deadline");
        equal(0, buffer.getCompletedSlots(), "stop does not append queued fragments");
        check(!buffer.isComplete(), "early cancellation is not completion");
        check(!buffer.isClosed(), "producer does not own buffer closure");
        equal(0, occurrences(statuses, "COMPLETE"), "cancellation emits no COMPLETE");
        equal(0, occurrences(statuses, "FAILED"), "expected user stop is not a fixture failure");
      } finally { producer.close(); }
    }
  }

  private static RecordingSink parse(int count, int slotBytes, byte[] bytes, int chunk) throws Exception {
    RecordingSink sink = new RecordingSink();
    try (FixedBudgetMp4 parser = new FixedBudgetMp4(count, slotBytes, sink)) {
      writeChunks(parser, bytes, chunk);
      check(parser.isComplete(), "parser must reach requested quota");
      equal(count, parser.getEmittedSlots(), "parser emitted slots");
      equal(bytes.length, parser.getRawBytes(), "all raw bytes consumed");
    }
    return sink;
  }

  private static void rejectParser(byte[] bytes, int count, int slotBytes) throws Exception {
    RecordingSink sink = new RecordingSink();
    FixedBudgetMp4 parser = new FixedBudgetMp4(count, slotBytes, sink);
    try {
      expectIOException(() -> writeChunks(parser, bytes, 1));
      equal(0, sink.slots.size(), "unsupported input emits no media slot");
      equal(0, sink.completions, "unsupported input emits no COMPLETE");
    } finally { closeQuietly(parser); }
  }

  private static void writeChunks(FixedBudgetMp4 parser, byte[] bytes, int chunk) throws IOException {
    for (int offset = 0; offset < bytes.length; offset += chunk) {
      int length = Math.min(chunk, bytes.length - offset);
      ByteBuffer input = ByteBuffer.wrap(bytes, offset, length);
      equal(length, parser.write(input), "channel consumes supplied chunk");
      equal(offset + length, input.position(), "input position tracks consumption");
    }
  }

  private static byte[] readFully(BoundedMediaBuffer buffer, long offset, int length, long waitMs)
      throws Exception {
    byte[] bytes = new byte[length];
    int read = 0;
    while (read < length) {
      int count = buffer.readAt(offset + read, bytes, read, length - read, waitMs);
      check(count > 0 && count <= length - read, "positive read before requested data ends");
      read += count;
    }
    return bytes;
  }

  private static byte[] fixture(int count) {
    List<byte[]> boxes = new ArrayList<>();
    byte[] init = initialization();
    boxes.add(init);
    long rawOffset = init.length;
    for (int index = 0; index < count; index++) {
      byte[] fragment = fragment(false, false, rawOffset, index + 1, index * 1024L);
      boxes.add(fragment);
      rawOffset += fragment.length;
    }
    return concat(boxes.toArray(new byte[0][]));
  }

  private static byte[] initialization() { return concat(ftyp(false), moov()); }
  private static byte[] initializationWithDefaultDuration(Integer duration) {
    byte[] contents = duration == null ? new byte[0] : box("mvex", fullBox("trex", 0,
        concat(intBytes(1), intBytes(1), intBytes(duration), intBytes(0), intBytes(0))));
    return concat(ftyp(false), box("moov", contents));
  }
  private static byte[] ftyp(boolean extended) {
    byte[] payload = concat(ascii("isom"), intBytes(512), ascii("isomiso6mp41"));
    return extended ? concat(intBytes(1), ascii("ftyp"), longBytes(payload.length + 16L), payload) : box("ftyp", payload);
  }
  private static byte[] moov() { return box("moov", mvex()); }
  private static byte[] mvex() {
    return box("mvex", fullBox("trex", 0, concat(intBytes(1), intBytes(1), intBytes(512), intBytes(0), intBytes(0))));
  }

  private static byte[] fragment(boolean absolute, boolean baseInMdat, long moofOffset, int sequence, long decodeTime) {
    int flags = absolute ? 1 : DEFAULT_BASE_IS_MOOF;
    byte[] provisionalTfhd = fullBox("tfhd", flags, concat(intBytes(1), absolute ? longBytes(0) : new byte[0]));
    byte[] tfdt = fullBox("tfdt", 0x01000000, longBytes(decodeTime));
    byte[] provisionalTrun = trun(0);
    byte[] provisional = box("moof", concat(fullBox("mfhd", 0, intBytes(sequence)),
        box("traf", concat(provisionalTfhd, tfdt, provisionalTrun))));
    long base = moofOffset + (baseInMdat ? provisional.length + 8L : 0L);
    int offset = baseInMdat ? 0 : provisional.length + 8;
    byte[] tfhd = fullBox("tfhd", flags, concat(intBytes(1), absolute ? longBytes(base) : new byte[0]));
    byte[] moof = box("moof", concat(fullBox("mfhd", 0, intBytes(sequence)), box("traf", concat(tfhd, tfdt, trun(offset)))));
    return concat(moof, box("mdat", SAMPLES));
  }

  private static byte[] trun(int dataOffset) {
    return fullBox("trun", 0x000701, concat(intBytes(2), intBytes(dataOffset),
        intBytes(512), intBytes(5), intBytes(0x02000000),
        intBytes(512), intBytes(7), intBytes(0x01010000)));
  }

  private static byte[] fragmentWithoutTfdt(boolean absolute, long moofOffset, int sequence,
      Integer sampleDuration, Integer defaultDuration) {
    int tfhdFlags = (absolute ? 1 : DEFAULT_BASE_IS_MOOF) | (defaultDuration == null ? 0 : 8);
    byte[] tfhdTail = defaultDuration == null ? new byte[0] : intBytes(defaultDuration);
    byte[] provisionalTfhd = fullBox("tfhd", tfhdFlags,
        concat(intBytes(1), absolute ? longBytes(0) : new byte[0], tfhdTail));
    byte[] provisional = box("moof", concat(fullBox("mfhd", 0, intBytes(sequence)),
        box("traf", concat(provisionalTfhd, trunWithDuration(0, sampleDuration)))));
    byte[] tfhd = fullBox("tfhd", tfhdFlags,
        concat(intBytes(1), absolute ? longBytes(moofOffset) : new byte[0], tfhdTail));
    byte[] moof = box("moof", concat(fullBox("mfhd", 0, intBytes(sequence)),
        box("traf", concat(tfhd, trunWithDuration(provisional.length + 8, sampleDuration)))));
    return concat(moof, box("mdat", SAMPLES));
  }

  private static byte[] trunWithDuration(int dataOffset, Integer duration) {
    byte[] encodedDuration = duration == null ? new byte[0] : intBytes(duration);
    int flags = 0x000601 | (duration == null ? 0 : 0x000100);
    return fullBox("trun", flags, concat(intBytes(2), intBytes(dataOffset),
        encodedDuration, intBytes(5), intBytes(0x02000000),
        encodedDuration, intBytes(7), intBytes(0x01010000)));
  }

  private static void assertSlot(byte[] slot, int rawBytes) {
    List<Box> boxes = boxes(slot, 0, slot.length);
    equal(rawBytes == slot.length ? 2 : 3, boxes.size(), "slot has moof, mdat and optional top-level free");
    equal("moof", boxes.get(0).type, "moof precedes mdat");
    equal("mdat", boxes.get(1).type, "mdat follows moof");
    equal(rawBytes, boxes.get(1).start + boxes.get(1).size, "padding is outside mdat");
    if (rawBytes < slot.length) {
      equal("free", boxes.get(2).type, "padding uses a real top-level free box");
      equal(slot.length - rawBytes, boxes.get(2).size, "padding box declares its exact size");
      check(boxes.get(2).size >= 8, "padding free box includes a complete header");
    }
  }

  private static Box top(byte[] bytes, String type) { return find(boxes(bytes, 0, bytes.length), type); }
  private static Box child(byte[] bytes, Box parent, String type) {
    return find(boxes(bytes, parent.start + parent.header, parent.start + parent.size), type);
  }
  private static Box find(List<Box> boxes, String type) {
    for (Box box : boxes) if (box.type.equals(type)) return box;
    throw new AssertionError("missing synthetic/output box " + type);
  }
  private static List<Box> boxes(byte[] bytes, int start, int end) {
    List<Box> boxes = new ArrayList<>();
    int offset = start;
    while (offset < end) {
      check(end - offset >= 8, "complete MP4 box header");
      long size = Integer.toUnsignedLong(intAt(bytes, offset));
      int header = size == 1 ? 16 : 8;
      if (size == 1) {
        check(end - offset >= 16, "complete extended box header");
        size = longAt(bytes, offset + 8);
      }
      check(size >= header && size <= end - offset, "bounded declared MP4 box size");
      boxes.add(new Box(offset, (int) size, header, new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)));
      offset += (int) size;
    }
    equal(end, offset, "boxes exactly fill container");
    return boxes;
  }
  private static byte[] payload(byte[] bytes, Box box) { return Arrays.copyOfRange(bytes, box.start + box.header, box.start + box.size); }
  private static byte[] fullBox(String type, int flags, byte[] payload) { return box(type, concat(intBytes(flags), payload)); }
  private static byte[] box(String type, byte[] payload) { return concat(intBytes(payload.length + 8), ascii(type), payload); }
  private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
  private static byte[] intBytes(int value) { return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(); }
  private static byte[] longBytes(long value) { return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array(); }
  private static int intAt(byte[] bytes, int offset) { return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt(); }
  private static long longAt(byte[] bytes, int offset) { return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong(); }
  private static void putInt(byte[] bytes, int offset, int value) { ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value); }
  private static void putLong(byte[] bytes, int offset, long value) { ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(value); }
  private static byte[] concat(byte[]... pieces) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (byte[] piece : pieces) bytes.write(piece, 0, piece.length);
    return bytes.toByteArray();
  }
  private static byte[] slotBytes(int seed) {
    byte[] bytes = new byte[SLOT];
    for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (seed * 17 + index);
    return bytes;
  }
  private static void closeQuietly(AutoCloseable closeable) {
    try { closeable.close(); } catch (Exception ignored) { /* Expected incomplete parser cleanup. */ }
  }
  private static int occurrences(List<String> values, String selected) {
    int count = 0;
    for (String value : values) if (selected.equals(value)) count++;
    return count;
  }
  private static void safeStatuses(List<String> statuses) {
    for (String status : statuses) check(status.equals("STARTED") || status.equals("COMPLETE") || status.equals("FAILED"), "fixed safe producer status");
  }
  private static void await(Check condition, long timeoutMs, String message) throws Exception {
    long started = System.nanoTime();
    while (!condition.get()) {
      if (elapsed(started) >= timeoutMs) throw new AssertionError(message);
      Thread.sleep(10);
    }
  }
  private static void expectIOException(Action action) throws Exception {
    try { action.run(); } catch (IOException expected) { return; }
    throw new AssertionError("expected IOException");
  }
  private static void expectTimeout(Action action) throws Exception {
    try { action.run(); } catch (SocketTimeoutException expected) { return; }
    throw new AssertionError("expected SocketTimeoutException");
  }
  private static void expectIllegalArgument(Action action) throws Exception {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("expected IllegalArgumentException for unbounded configuration");
  }
  private static NamedCase test(String name, Action body) { return new NamedCase(name, body); }
  private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  private static void equal(long expected, long actual, String description) { check(expected == actual, description + ": expected " + expected + ", got " + actual); }
  private static void equal(String expected, String actual, String description) { check(expected.equals(actual), description + ": expected " + expected + ", got " + actual); }
  private static void equalBytes(byte[] expected, byte[] actual, String description) { check(Arrays.equals(expected, actual), description + ": expected " + expected.length + " exact bytes, got " + actual.length); }

  @FunctionalInterface private interface Action { void run() throws Exception; }
  @FunctionalInterface private interface Check { boolean get() throws Exception; }
  private static final class NamedCase {
    final String name;
    final Action body;
    NamedCase(String name, Action body) { this.name = name; this.body = body; }
  }
  private static final class Box {
    final int start;
    final int size;
    final int header;
    final String type;
    Box(int start, int size, int header, String type) { this.start = start; this.size = size; this.header = header; this.type = type; }
  }
  private static final class RecordingSink implements FixedBudgetMp4.Sink {
    final List<byte[]> initializations = new ArrayList<>();
    final List<byte[]> slots = new ArrayList<>();
    int completions;
    @Override public void onInitialization(byte[] init) { initializations.add(init); }
    @Override public void onFragment(byte[] slot) { slots.add(slot); }
    @Override public void onComplete() { completions++; }
  }
  private static final class Worker<T> implements AutoCloseable {
    final FutureTask<T> task;
    final Thread thread;
    Worker(Callable<T> callable) {
      task = new FutureTask<>(callable);
      thread = new Thread(task, "fixed-budget-test-waiter");
      thread.setDaemon(true);
      thread.start();
    }
    boolean isDone() { return task.isDone(); }
    T get(long timeoutMs) throws Exception {
      try { return task.get(timeoutMs, TimeUnit.MILLISECONDS); }
      catch (ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof Exception) throw (Exception) cause;
        if (cause instanceof Error) throw (Error) cause;
        throw new AssertionError(cause);
      }
    }
    @Override public void close() throws InterruptedException {
      thread.interrupt();
      thread.join(1000);
      check(!thread.isAlive(), "test waiter terminates");
    }
  }
}
