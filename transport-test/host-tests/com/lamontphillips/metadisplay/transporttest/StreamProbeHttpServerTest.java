package com.lamontphillips.metadisplay.transporttest;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Plain-Java loopback protocol tests using only deterministic, synthetic public bytes. */
public final class StreamProbeHttpServerTest {
  private static final byte[] CONTENT = syntheticContent();
  private static final int SOCKET_TIMEOUT_MS = 6000;
  private static final long WAIT_MS = 6000;

  private StreamProbeHttpServerTest() {}

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    tests.add(new NamedCase("initial transfer snapshot and lifecycle", StreamProbeHttpServerTest::initialSnapshot));
    tests.add(new NamedCase("FILE full GET and transfer events", StreamProbeHttpServerTest::fileGet));
    tests.add(new NamedCase("FILE HEAD does not create a transfer", () -> head(StreamProbeHttpServer.Delivery.FILE, false)));
    tests.add(new NamedCase("FILE HEAD range", () -> head(StreamProbeHttpServer.Delivery.FILE, true)));
    tests.add(new NamedCase("FILE bounded range", () -> fileRange("bytes=7-30", 7, 30)));
    tests.add(new NamedCase("FILE open range", () -> fileRange("bytes=262100-", 262100, CONTENT.length - 1)));
    tests.add(new NamedCase("FILE suffix range", () -> fileRange("bytes=-13", CONTENT.length - 13, CONTENT.length - 1)));
    tests.add(new NamedCase("FILE range end clamps", () -> fileRange("bytes=262120-999999", 262120, CONTENT.length - 1)));
    for (String range : new String[] {"bytes=262144-", "bytes=9-8", "bytes=1-2,4-5", "bytes=0-9223372036854775808"}) {
      tests.add(new NamedCase("FILE rejects range " + range, () -> invalidRange(range)));
    }
    tests.add(new NamedCase("PACED_FILE sends an early partial payload", StreamProbeHttpServerTest::pacedFile));
    tests.add(new NamedCase("PACED_FILE range duration scales with payload", StreamProbeHttpServerTest::pacedRange));
    tests.add(new NamedCase("PACED_FILE HEAD ignores pacing and EOF hold", () -> head(StreamProbeHttpServer.Delivery.PACED_FILE, true)));
    tests.add(new NamedCase("PACED_FILE payload precedes response completion", () -> eofHold(StreamProbeHttpServer.Delivery.PACED_FILE, false)));
    tests.add(new NamedCase("PACED_CHUNKED decodes exact payload", StreamProbeHttpServerTest::chunkedGet));
    tests.add(new NamedCase("PACED_CHUNKED ignores bounded Range", () -> chunkedRangeIgnored("bytes=7-30")));
    tests.add(new NamedCase("PACED_CHUNKED ignores unsatisfiable Range", () -> chunkedRangeIgnored("bytes=9223372036854775808-")));
    tests.add(new NamedCase("PACED_CHUNKED HEAD metadata without chunks", () -> head(StreamProbeHttpServer.Delivery.PACED_CHUNKED, false)));
    tests.add(new NamedCase("PACED_CHUNKED HEAD ignores Range", () -> head(StreamProbeHttpServer.Delivery.PACED_CHUNKED, true)));
    tests.add(new NamedCase("PACED_CHUNKED delays terminal chunk through EOF hold", () -> eofHold(StreamProbeHttpServer.Delivery.PACED_CHUNKED, false)));
    tests.add(new NamedCase("stop interrupts paced payload", StreamProbeHttpServerTest::stopDuringPayload));
    tests.add(new NamedCase("stop interrupts FILE EOF hold", () -> eofHold(StreamProbeHttpServer.Delivery.PACED_FILE, true)));
    tests.add(new NamedCase("stop interrupts CHUNKED EOF hold", () -> eofHold(StreamProbeHttpServer.Delivery.PACED_CHUNKED, true)));
    tests.add(new NamedCase("latest GET owns snapshot across older completion", StreamProbeHttpServerTest::overlappingGets));
    tests.add(new NamedCase("HEAD cannot replace an active GET snapshot", StreamProbeHttpServerTest::headPreservesSnapshot));

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
    System.out.println("StreamProbeHttpServerTest: " + (tests.size() - failures) + "/"
        + tests.size() + " passed; " + failures + " failed");
    if (failures != 0) System.exit(1);
  }

  private static void initialSnapshot() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.FILE, 0, 0)) {
      check(fixture.server.isRunning(), "server running");
      check(fixture.server.getPort() > 0, "ephemeral bound port");
      check(fixture.server.getPath().matches("/[^/]+/sample\\.mp4"), "tokenized asset path");
      emptySnapshot(fixture.server.getTransferSnapshot());
      equal(0, fixture.server.getRequestCount(), "initial request count");
      equal(0, fixture.server.getBytesServed(), "initial byte count");
      fixture.server.close();
      fixture.server.close();
      check(!fixture.server.isRunning(), "idempotent close clears running state");
    }
  }

  private static void fileGet() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.FILE, 0, 0);
        Client client = fixture.request("GET", "")) {
      fileHeaders(client, 200, CONTENT.length);
      equalBytes(CONTENT, client.readFixed(CONTENT.length), "FILE body");
      client.expectEof();
      StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
      event(started, StreamProbeHttpServer.Phase.STARTED, 200, 0, 0, CONTENT.length - 1);
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
      event(complete, StreamProbeHttpServer.Phase.COMPLETE, 200, CONTENT.length, 0, CONTENT.length - 1);
      completedSnapshot(fixture, started.transferId, CONTENT.length, 0);
      eventOrder(fixture, started.transferId, CONTENT.length, false);
      equal(1, fixture.server.getRequestCount(), "GET count");
      equal(CONTENT.length, fixture.server.getBytesServed(), "GET body byte count");
    }
  }

  private static void head(StreamProbeHttpServer.Delivery delivery, boolean range) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 1800, 1800)) {
      long started = System.nanoTime();
      try (Client client = fixture.request("HEAD", range ? "Range: bytes=7-30\r\n" : "")) {
        if (delivery == StreamProbeHttpServer.Delivery.PACED_CHUNKED) {
          chunkedHeaders(client);
        } else if (range) {
          fileRangeHeaders(client, 7, 30);
        } else {
          fileHeaders(client, 200, CONTENT.length);
        }
        client.expectEof();
      }
      check(elapsed(started) < 1200, "HEAD must not wait for pacing or EOF hold");
      emptySnapshot(fixture.server.getTransferSnapshot());
      equal(1, fixture.server.getRequestCount(), "HEAD is an asset request");
      equal(0, fixture.server.getBytesServed(), "HEAD sends no body or chunk terminator");
    }
  }

  private static void fileRange(String range, int first, int last) throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.FILE, 0, 0);
        Client client = fixture.request("GET", "Range: " + range + "\r\n")) {
      fileRangeHeaders(client, first, last);
      int length = last - first + 1;
      equalBytes(Arrays.copyOfRange(CONTENT, first, last + 1), client.readFixed(length), "FILE range body");
      client.expectEof();
      StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
      event(started, StreamProbeHttpServer.Phase.STARTED, 206, 0, first, last);
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
      event(complete, StreamProbeHttpServer.Phase.COMPLETE, 206, length, first, last);
      completedSnapshot(fixture, started.transferId, length, 0);
    }
  }

  private static void invalidRange(String range) throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.FILE, 0, 0);
        Client client = fixture.request("GET", "Range: " + range + "\r\n")) {
      equal(416, client.status, "invalid range status");
      equal("bytes */" + CONTENT.length, client.header("content-range"), "unsatisfied Content-Range");
      equal("0", client.header("content-length"), "invalid range has empty body");
      client.expectEof();
      emptySnapshot(fixture.server.getTransferSnapshot());
      equal(0, fixture.server.getBytesServed(), "invalid range writes no payload");
    }
  }

  private static void pacedFile() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_FILE, 1100, 0)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("GET", "")) {
        fileHeaders(client, 200, CONTENT.length);
        StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
        event(started, StreamProbeHttpServer.Phase.STARTED, 200, 0, 0, CONTENT.length - 1);
        byte[] first = client.readFixed(1);
        StreamProbeHttpServer.TransferSnapshot partial = fixture.snapshot(value -> value.id == started.transferId
            && value.bytesSent > 0 && value.bytesSent < CONTENT.length);
        equal(CONTENT.length, partial.plannedBytes, "paced planned bytes");
        equal(1, partial.activeGetCount, "paced GET is active");
        check(!partial.payloadComplete && !partial.responseComplete, "partial payload flags remain false");
        equalBytes(CONTENT, concatenate(first, client.readFixed(CONTENT.length - 1)), "paced FILE exact bytes");
        client.expectEof();
        check(elapsed(startedAt) >= 650, "pacing must spread payload over time");
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
        completedSnapshot(fixture, started.transferId, CONTENT.length, 0);
        eventOrder(fixture, started.transferId, CONTENT.length, true);
      }
    }
  }

  private static void pacedRange() throws Exception {
    // A quarter of the asset should take about a quarter of the full 2-second duration.
    int length = CONTENT.length / 4;
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_FILE, 2000, 0)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("GET", "Range: bytes=0-" + (length - 1) + "\r\n")) {
        fileRangeHeaders(client, 0, length - 1);
        equalBytes(Arrays.copyOfRange(CONTENT, 0, length), client.readFixed(length), "paced range exact bytes");
        client.expectEof();
        long took = elapsed(startedAt);
        check(took >= 200, "range must retain scaled pacing");
        check(took < 1500, "range must scale down from the full-asset duration; took " + took + "ms");
        StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
        completedSnapshot(fixture, complete.transferId, length, 0);
      }
    }
  }

  private static void chunkedGet() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_CHUNKED, 600, 0);
        Client client = fixture.request("GET", "")) {
      chunkedHeaders(client);
      equalBytes(CONTENT, client.readChunkedPayload(CONTENT.length), "decoded chunked payload");
      client.readChunkTerminator();
      client.expectEof();
      StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
      event(started, StreamProbeHttpServer.Phase.STARTED, 200, 0, 0, CONTENT.length - 1);
      fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
      completedSnapshot(fixture, started.transferId, CONTENT.length, 0);
      equal(CONTENT.length, fixture.server.getBytesServed(), "chunk framing is excluded from payload counters");
      eventOrder(fixture, started.transferId, CONTENT.length, true);
    }
  }

  private static void chunkedRangeIgnored(String range) throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_CHUNKED, 0, 0);
        Client client = fixture.request("GET", "Range: " + range + "\r\n")) {
      chunkedHeaders(client);
      equalBytes(CONTENT, client.readChunkedPayload(CONTENT.length), "ignored Range returns whole chunked asset");
      client.readChunkTerminator();
      client.expectEof();
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
      event(complete, StreamProbeHttpServer.Phase.COMPLETE, 200, CONTENT.length, 0, CONTENT.length - 1);
      equal("served_range_ignored", complete.outcome, "fixed safe Range-ignored outcome");
      completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
    }
  }

  private static void eofHold(StreamProbeHttpServer.Delivery delivery, boolean stop) throws Exception {
    long holdMs = stop ? 2500 : 850;
    try (Fixture fixture = new Fixture(delivery, 250, holdMs);
        Client client = fixture.request("GET", "")) {
      if (delivery == StreamProbeHttpServer.Delivery.PACED_CHUNKED) {
        chunkedHeaders(client);
        equalBytes(CONTENT, client.readChunkedPayload(CONTENT.length), "payload before chunk EOF hold");
      } else {
        fileHeaders(client, 200, CONTENT.length);
        equalBytes(CONTENT, client.readFixed(CONTENT.length), "payload before FILE EOF hold");
      }
      StreamProbeHttpServer.TransferSnapshot held = fixture.snapshot(value -> value.payloadComplete
          && !value.responseComplete && value.activeGetCount == 1);
      equal(CONTENT.length, held.bytesSent, "all payload bytes precede EOF completion");
      equal(CONTENT.length, held.plannedBytes, "held response planned bytes");
      check(fixture.history.stream().noneMatch(event -> event.transferId == held.id
          && event.phase == StreamProbeHttpServer.Phase.COMPLETE), "no COMPLETE event during EOF hold");
      if (stop) {
        long stoppedAt = System.nanoTime();
        fixture.server.close();
        check(elapsed(stoppedAt) < 1800, "stop must interrupt EOF hold");
        check(!fixture.server.isRunning(), "server stopped during EOF hold");
        byte[] leftover = client.readUntilClosedAllowReset();
        equal(0, leftover.length, "stop must not write a successful terminal chunk after abort");
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, held.id);
        StreamProbeHttpServer.TransferSnapshot aborted = fixture.snapshot(value -> value.activeGetCount == 0);
        equal(held.id, aborted.id, "EOF abort retains transfer identity");
        check(aborted.payloadComplete && !aborted.responseComplete, "EOF abort is distinct from successful completion");
      } else {
        client.expectStillOpen(150);
        if (delivery == StreamProbeHttpServer.Delivery.PACED_CHUNKED) client.readChunkTerminator();
        client.expectEof();
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, held.id);
        completedSnapshot(fixture, held.id, CONTENT.length, 0);
      }
    }
  }

  private static void stopDuringPayload() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_FILE, 2200, 1000);
        Client client = fixture.request("GET", "")) {
      fileHeaders(client, 200, CONTENT.length);
      client.readFixed(1);
      StreamProbeHttpServer.TransferSnapshot partial = fixture.snapshot(value -> value.id > 0
          && value.bytesSent > 0 && value.bytesSent < value.plannedBytes);
      long stoppedAt = System.nanoTime();
      fixture.server.close();
      check(elapsed(stoppedAt) < 1800, "stop interrupts payload pacing");
      client.readUntilClosedAllowReset();
      fixture.event(StreamProbeHttpServer.Phase.COMPLETE, partial.id);
      StreamProbeHttpServer.TransferSnapshot aborted = fixture.snapshot(value -> value.activeGetCount == 0);
      equal(partial.id, aborted.id, "aborted transfer identity");
      check(aborted.bytesSent < aborted.plannedBytes, "aborted transfer remains partial");
      check(!aborted.payloadComplete && !aborted.responseComplete, "partial abort completion flags");
      check(!fixture.server.isRunning(), "server remains stopped");
    }
  }

  private static void overlappingGets() throws Exception {
    int newerLength = CONTENT.length / 4;
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_FILE, 1800, 500);
        Client older = fixture.request("GET", "")) {
      fileHeaders(older, 200, CONTENT.length);
      byte[] firstOlder = older.readFixed(1);
      StreamProbeHttpServer.TransferSnapshot first = fixture.snapshot(value -> value.id > 0 && value.bytesSent > 0);
      try (Client newer = fixture.request("GET", "Range: bytes=0-" + (newerLength - 1) + "\r\n")) {
        fileRangeHeaders(newer, 0, newerLength - 1);
        StreamProbeHttpServer.TransferSnapshot latest = fixture.snapshot(value -> value.id != first.id
            && value.id > 0 && value.activeGetCount == 2);
        equal(newerLength, latest.plannedBytes, "newer GET owns planned bytes");
        equalBytes(Arrays.copyOfRange(CONTENT, 0, newerLength), newer.readFixed(newerLength), "newer ranged GET bytes");
        newer.expectEof();
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, latest.id);
        completedSnapshot(fixture, latest.id, newerLength, 1);
        equalBytes(CONTENT, concatenate(firstOlder, older.readFixed(CONTENT.length - 1)), "older GET remains intact");
        older.expectEof();
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, first.id);
        completedSnapshot(fixture, latest.id, newerLength, 0);
        equal(2, fixture.server.getRequestCount(), "overlapping asset GET count");
        equal((long) CONTENT.length + newerLength, fixture.server.getBytesServed(), "overlapping total payload bytes");
      }
    }
  }

  private static void headPreservesSnapshot() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_FILE, 1100, 250);
        Client get = fixture.request("GET", "")) {
      fileHeaders(get, 200, CONTENT.length);
      byte[] first = get.readFixed(1);
      StreamProbeHttpServer.TransferSnapshot original = fixture.snapshot(value -> value.id > 0 && value.bytesSent > 0);
      try (Client head = fixture.request("HEAD", "Range: bytes=7-30\r\n")) {
        fileRangeHeaders(head, 7, 30);
        head.expectEof();
      }
      StreamProbeHttpServer.TransferSnapshot afterHead = fixture.server.getTransferSnapshot();
      equal(original.id, afterHead.id, "HEAD does not replace GET identity");
      equal(CONTENT.length, afterHead.plannedBytes, "HEAD does not replace GET planned bytes");
      equal(1, afterHead.activeGetCount, "HEAD is not an active GET");
      equalBytes(CONTENT, concatenate(first, get.readFixed(CONTENT.length - 1)), "GET body survives HEAD");
      get.expectEof();
      fixture.event(StreamProbeHttpServer.Phase.COMPLETE, original.id);
      completedSnapshot(fixture, original.id, CONTENT.length, 0);
      equal(2, fixture.server.getRequestCount(), "GET plus HEAD request count");
      equal(CONTENT.length, fixture.server.getBytesServed(), "HEAD contributes no payload bytes");
    }
  }

  private static void emptySnapshot(StreamProbeHttpServer.TransferSnapshot snapshot) {
    equal(0, snapshot.id, "no GET transfer identity");
    equal(0, snapshot.bytesSent, "no GET payload bytes");
    equal(0, snapshot.plannedBytes, "no GET planned bytes");
    equal(0, snapshot.activeGetCount, "no active GET");
    check(!snapshot.payloadComplete && !snapshot.responseComplete, "no GET completion flags");
  }

  private static void completedSnapshot(Fixture fixture, long id, long length, int active) throws Exception {
    StreamProbeHttpServer.TransferSnapshot snapshot = fixture.snapshot(value -> value.id == id
        && value.payloadComplete && value.responseComplete && value.activeGetCount == active);
    equal(length, snapshot.bytesSent, "completed snapshot body bytes");
    equal(length, snapshot.plannedBytes, "completed snapshot planned bytes");
  }

  private static void event(StreamProbeHttpServer.Event event, StreamProbeHttpServer.Phase phase,
      int status, long bytes, long first, long last) {
    check(event.phase == phase, "event phase");
    check(event.transferId > 0, "successful GET event transfer ID");
    equal("GET", event.method, "event method");
    equal(status, event.status, "event status");
    equal(bytes, event.bytesSent, "event payload bytes");
    equal(first, event.start, "event inclusive start");
    equal(last, event.end, "event inclusive end");
    check(event.assetRequest, "successful GET asset event");
    check(event.outcome != null && !event.outcome.isEmpty(), "event fixed outcome is present");
  }

  private static void eventOrder(Fixture fixture, long id, long bytes, boolean paced) {
    int starts = 0;
    int progress = 0;
    int completes = 0;
    long previousBytes = -1;
    boolean ended = false;
    for (StreamProbeHttpServer.Event event : fixture.history) {
      if (event.transferId != id) continue;
      check(!ended, "no transfer event follows COMPLETE");
      check(event.bytesSent >= previousBytes && event.bytesSent <= bytes, "monotonic event payload bytes");
      previousBytes = event.bytesSent;
      if (event.phase == StreamProbeHttpServer.Phase.STARTED) {
        starts++;
        equal(0, event.bytesSent, "STARTED precedes payload");
      } else if (event.phase == StreamProbeHttpServer.Phase.PROGRESS) {
        progress++;
        check(starts == 1, "PROGRESS follows STARTED");
      } else if (event.phase == StreamProbeHttpServer.Phase.COMPLETE) {
        completes++;
        ended = true;
        equal(bytes, event.bytesSent, "COMPLETE contains all payload bytes");
      }
    }
    equal(1, starts, "one STARTED event per GET");
    equal(1, completes, "one COMPLETE event per GET");
    if (paced) {
      check(progress >= 1, "paced GET reports payload-end progress");
      check(progress <= 4, "short paced GET must not emit progress for every chunk");
    }
  }

  private static void fileHeaders(Client client, int status, int length) {
    equal(status, client.status, "HTTP status");
    equal("video/mp4", client.header("content-type"), "MP4 content type");
    equal(Integer.toString(length), client.header("content-length"), "Content-Length");
    equal("bytes", client.header("accept-ranges"), "FILE Accept-Ranges");
    check(!client.headers.containsKey("transfer-encoding"), "FILE has no Transfer-Encoding");
  }

  private static void fileRangeHeaders(Client client, int first, int last) {
    fileHeaders(client, 206, last - first + 1);
    equal("bytes " + first + "-" + last + "/" + CONTENT.length,
        client.header("content-range"), "Content-Range");
  }

  private static void chunkedHeaders(Client client) {
    equal(200, client.status, "chunked full-response status");
    equal("video/mp4", client.header("content-type"), "chunked content type");
    equal("chunked", client.header("transfer-encoding").toLowerCase(Locale.ROOT), "Transfer-Encoding");
    equal("none", client.header("accept-ranges"), "chunked Accept-Ranges");
    check(!client.headers.containsKey("content-length"), "chunked has no Content-Length");
    check(!client.headers.containsKey("content-range"), "ignored Range has no Content-Range");
  }

  private static byte[] syntheticContent() {
    byte[] content = new byte[256 * 1024];
    for (int index = 0; index < content.length; index++) {
      content[index] = (byte) ((index * 31 + index / 11) & 0xff);
    }
    return content;
  }

  private static byte[] concatenate(byte[] first, byte[] rest) {
    byte[] result = Arrays.copyOf(first, first.length + rest.length);
    System.arraycopy(rest, 0, result, first.length, rest.length);
    return result;
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  private static void equal(long expected, long actual, String description) {
    check(expected == actual, description + ": expected " + expected + ", got " + actual);
  }

  private static void equal(String expected, String actual, String description) {
    check(expected.equals(actual), description + ": expected " + expected + ", got " + actual);
  }

  private static void equalBytes(byte[] expected, byte[] actual, String description) {
    check(Arrays.equals(expected, actual), description + ": expected " + expected.length
        + " exact bytes, got " + actual.length);
  }

  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }

  @FunctionalInterface
  private interface TestCase { void run() throws Exception; }

  private static final class NamedCase {
    final String name;
    final TestCase body;
    NamedCase(String name, TestCase body) { this.name = name; this.body = body; }
  }

  private static final class Fixture implements AutoCloseable {
    final StreamProbeHttpServer server;
    final InetAddress address = InetAddress.getLoopbackAddress();
    final List<StreamProbeHttpServer.Event> history = new CopyOnWriteArrayList<>();
    final BlockingQueue<StreamProbeHttpServer.Event> events = new LinkedBlockingQueue<>();

    Fixture(StreamProbeHttpServer.Delivery delivery, long durationMs, long holdMs) throws IOException {
      server = new StreamProbeHttpServer(CONTENT.clone(), address, delivery, durationMs, holdMs,
          event -> { history.add(event); events.add(event); });
      try {
        server.start();
      } catch (IOException | RuntimeException failure) {
        server.close();
        throw failure;
      }
    }

    Client request(String method, String headers) throws IOException {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, server.getPort()), 2000);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        String text = method + " " + server.getPath() + " HTTP/1.1\r\nHost: localhost\r\n"
            + "Connection: close\r\n" + headers + "\r\n";
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return new Client(socket);
      } catch (IOException | RuntimeException | Error failure) {
        socket.close();
        throw failure;
      }
    }

    StreamProbeHttpServer.Event event(StreamProbeHttpServer.Phase phase, long id) throws Exception {
      long started = System.nanoTime();
      while (elapsed(started) < WAIT_MS) {
        StreamProbeHttpServer.Event event = events.poll(Math.max(1, WAIT_MS - elapsed(started)), TimeUnit.MILLISECONDS);
        if (event == null) break;
        if (event.phase == phase && (id == 0 || event.transferId == id)) return event;
      }
      throw new AssertionError("missing " + phase + " event for transfer " + id);
    }

    StreamProbeHttpServer.TransferSnapshot snapshot(Predicate<StreamProbeHttpServer.TransferSnapshot> condition)
        throws InterruptedException {
      long started = System.nanoTime();
      StreamProbeHttpServer.TransferSnapshot snapshot;
      do {
        snapshot = server.getTransferSnapshot();
        if (condition.test(snapshot)) return snapshot;
        Thread.sleep(10);
      } while (elapsed(started) < WAIT_MS);
      throw new AssertionError("snapshot condition not reached: id=" + snapshot.id + ", bytes="
          + snapshot.bytesSent + "/" + snapshot.plannedBytes + ", payloadComplete=" + snapshot.payloadComplete
          + ", responseComplete=" + snapshot.responseComplete + ", active=" + snapshot.activeGetCount);
    }

    @Override public void close() { server.close(); }
  }

  private static final class Client implements AutoCloseable {
    final Socket socket;
    final InputStream input;
    final int status;
    final Map<String, String> headers = new LinkedHashMap<>();

    Client(Socket socket) throws IOException {
      this.socket = socket;
      input = socket.getInputStream();
      String[] statusLine = readLine().split(" +", 3);
      check(statusLine.length >= 2 && statusLine[0].startsWith("HTTP/1."), "HTTP status line");
      status = Integer.parseInt(statusLine[1]);
      int headerCount = 0;
      while (true) {
        String line = readLine();
        if (line.isEmpty()) break;
        check(++headerCount <= 64, "bounded response header count");
        int colon = line.indexOf(':');
        check(colon > 0, "well-formed response header");
        String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
        check(!headers.containsKey(name), "duplicate response header " + name);
        headers.put(name, line.substring(colon + 1).trim());
      }
    }

    String header(String name) {
      String value = headers.get(name);
      check(value != null, "missing response header " + name);
      return value;
    }

    byte[] readFixed(int length) throws IOException {
      check(length >= 0 && length <= CONTENT.length, "bounded expected payload length");
      byte[] bytes = new byte[length];
      int offset = 0;
      while (offset < length) {
        int count = input.read(bytes, offset, length - offset);
        if (count < 0) throw new EOFException("payload ended at " + offset + " of " + length);
        offset += count;
      }
      return bytes;
    }

    byte[] readChunkedPayload(int expected) throws IOException {
      ByteArrayOutputStream payload = new ByteArrayOutputStream(expected);
      while (payload.size() < expected) {
        String line = readLine();
        String sizeText = line.split(";", 2)[0];
        check(sizeText.matches("[0-9a-fA-F]+"), "hexadecimal chunk size");
        long length = Long.parseLong(sizeText, 16);
        check(length > 0 && length <= expected - payload.size(), "bounded, nonterminal data chunk");
        byte[] bytes = readFixed((int) length);
        payload.write(bytes, 0, bytes.length);
        equal("", readLine(), "CRLF after each chunk payload");
      }
      return payload.toByteArray();
    }

    void readChunkTerminator() throws IOException {
      equal("0", readLine(), "terminal zero chunk");
      equal("", readLine(), "empty trailer section terminates chunked response");
    }

    String readLine() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      while (true) {
        int next = input.read();
        if (next < 0) throw new EOFException("response ended inside a protocol line");
        if (next == '\r') {
          check(input.read() == '\n', "protocol lines end in CRLF");
          return bytes.toString(StandardCharsets.ISO_8859_1.name());
        }
        check(next != '\n' && bytes.size() < 16384, "bounded CRLF protocol line");
        bytes.write(next);
      }
    }

    void expectStillOpen(int timeoutMs) throws IOException {
      socket.setSoTimeout(timeoutMs);
      try {
        int next = input.read();
        throw new AssertionError("EOF hold ended early or wrote terminal framing: " + next);
      } catch (SocketTimeoutException expected) {
        // Neither EOF nor a terminal chunk may be written during the hold.
      } finally {
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
      }
    }

    void expectEof() throws IOException {
      equal(-1, input.read(), "connection closes after exactly one response");
    }

    byte[] readUntilClosedAllowReset() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      try {
        int count;
        while ((count = input.read(buffer)) != -1) {
          check(bytes.size() + count <= CONTENT.length + 16384, "bounded abort remainder");
          bytes.write(buffer, 0, count);
        }
      } catch (SocketTimeoutException timeout) {
        throw new AssertionError("stopped server left its client connection open", timeout);
      } catch (SocketException expectedReset) {
        // Closing an active connection can produce a reset instead of clean EOF.
      }
      return bytes.toByteArray();
    }

    @Override public void close() throws IOException { socket.close(); }
  }
}
