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

    tests.add(new NamedCase("CHUNKED_FILE is immediate with exact chunk decoding", StreamProbeHttpServerTest::instantChunked));
    tests.add(new NamedCase("CLOSE_FILE is immediate with exact raw bytes", StreamProbeHttpServerTest::instantClose));
    tests.add(new NamedCase("PACED_CLOSE has an early partial raw payload", () -> pacedClose(StreamProbeHttpServer.Delivery.PACED_CLOSE)));
    tests.add(new NamedCase("PACED_CLOSE_HTTP10 has an early partial raw payload", () -> pacedClose(StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10)));
    for (StreamProbeHttpServer.Delivery delivery : new StreamProbeHttpServer.Delivery[] {
        StreamProbeHttpServer.Delivery.CHUNKED_FILE,
        StreamProbeHttpServer.Delivery.CLOSE_FILE,
        StreamProbeHttpServer.Delivery.PACED_CLOSE,
        StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10}) {
      tests.add(new NamedCase(delivery + " HEAD ignores Range and pacing", () -> unknownHead(delivery)));
      tests.add(new NamedCase(delivery + " ignores malformed Range", () -> unknownRangeIgnored(delivery)));
      tests.add(new NamedCase(delivery + " errors retain Content-Length zero", () -> unknownErrors(delivery)));
      tests.add(new NamedCase(delivery + " EOF hold separates payload and response", () -> unknownEofHold(delivery, false)));
      tests.add(new NamedCase(delivery + " stop interrupts EOF hold", () -> unknownEofHold(delivery, true)));
    }
    tests.add(new NamedCase("PACED_CLOSE cancellation retains partial flags", () -> stopDuringClosePayload(StreamProbeHttpServer.Delivery.PACED_CLOSE)));
    tests.add(new NamedCase("PACED_CLOSE_HTTP10 cancellation retains partial flags", () -> stopDuringClosePayload(StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10)));
    tests.add(new NamedCase("HTTP/1.0 close mode accepts an HTTP/1.0 request", StreamProbeHttpServerTest::http10Request));
    tests.add(new NamedCase("CHUNKED_FILE falls back to raw close for HTTP/1.0", () -> http10ChunkFallback(StreamProbeHttpServer.Delivery.CHUNKED_FILE)));
    tests.add(new NamedCase("PACED_CHUNKED falls back to raw close for HTTP/1.0", () -> http10ChunkFallback(StreamProbeHttpServer.Delivery.PACED_CHUNKED)));

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

  private static void instantChunked() throws Exception {
    // Configure nonzero pacing deliberately: this delivery mode must still be immediate.
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.CHUNKED_FILE, 1800, 0)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("GET", "Range: bytes=7-30\r\n")) {
        unknownHeaders(client, StreamProbeHttpServer.Delivery.CHUNKED_FILE);
        equalBytes(CONTENT, client.readChunkedPayload(CONTENT.length), "instant decoded chunked body");
        client.readChunkTerminator();
        client.expectEof();
        check(elapsed(startedAt) < 1200, "CHUNKED_FILE must not inherit configured pacing");
        StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
        event(complete, StreamProbeHttpServer.Phase.COMPLETE, 200, CONTENT.length, 0, CONTENT.length - 1);
        completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
        equal(CONTENT.length, fixture.server.getBytesServed(), "instant chunk framing is not payload");
      }
    }
  }

  private static void instantClose() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.CLOSE_FILE, 1800, 0)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("GET", "Range: bytes=7-30\r\n")) {
        unknownHeaders(client, StreamProbeHttpServer.Delivery.CLOSE_FILE);
        equalBytes(CONTENT, client.readFixed(CONTENT.length), "instant raw close-delimited body has no chunk framing");
        client.expectEof();
        check(elapsed(startedAt) < 1200, "CLOSE_FILE must not inherit configured pacing");
        StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
        event(complete, StreamProbeHttpServer.Phase.COMPLETE, 200, CONTENT.length, 0, CONTENT.length - 1);
        completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
        equal(CONTENT.length, fixture.server.getBytesServed(), "close-delimited body count");
      }
    }
  }

  private static void pacedClose(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 1000, 0)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("GET", "")) {
        unknownHeaders(client, delivery);
        StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
        event(started, StreamProbeHttpServer.Phase.STARTED, 200, 0, 0, CONTENT.length - 1);
        byte[] first = client.readFixed(1);
        StreamProbeHttpServer.TransferSnapshot partial = fixture.snapshot(value -> value.id == started.transferId
            && value.bytesSent > 0 && value.bytesSent < value.plannedBytes);
        equal(CONTENT.length, partial.plannedBytes, "close-delimited planned bytes");
        equal(1, partial.activeGetCount, "close-delimited GET active while paced");
        check(!partial.payloadComplete && !partial.responseComplete, "early close-delimited flags are partial");
        equalBytes(CONTENT, concatenate(first, client.readFixed(CONTENT.length - 1)),
            "paced raw body has neither chunk framing nor omitted bytes");
        client.expectEof();
        check(elapsed(startedAt) >= 600, "close-delimited payload must be paced");
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
        completedSnapshot(fixture, started.transferId, CONTENT.length, 0);
        eventOrder(fixture, started.transferId, CONTENT.length, true);
      }
    }
  }

  private static void unknownHead(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 1800, 1800)) {
      long startedAt = System.nanoTime();
      try (Client client = fixture.request("HEAD", "Range: bytes=7-30\r\n")) {
        unknownHeaders(client, delivery);
        client.expectEof();
      }
      check(elapsed(startedAt) < 1200, "unknown-length HEAD must not wait for pacing or EOF hold");
      emptySnapshot(fixture.server.getTransferSnapshot());
      equal(1, fixture.server.getRequestCount(), "HEAD is counted without an active GET");
      equal(0, fixture.server.getBytesServed(), "HEAD has no raw body or chunk terminator");
    }
  }

  private static void unknownRangeIgnored(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 120, 0);
        Client client = fixture.request("GET", "Range: bytes=0-9223372036854775808\r\nRange: bytes=9-8\r\n")) {
      unknownHeaders(client, delivery);
      equalBytes(CONTENT, readUnknownPayload(client, delivery), "ignored invalid Range still returns the whole asset");
      if (delivery == StreamProbeHttpServer.Delivery.CHUNKED_FILE) client.readChunkTerminator();
      client.expectEof();
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
      event(complete, StreamProbeHttpServer.Phase.COMPLETE, 200, CONTENT.length, 0, CONTENT.length - 1);
      equal("served_range_ignored", complete.outcome, "ignored Range uses a fixed safe outcome");
      completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
      transportMetadata(complete, fixture.server.getTransferSnapshot(), "HTTP/1.1",
          delivery == StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10 ? "HTTP/1.0" : "HTTP/1.1",
          delivery == StreamProbeHttpServer.Delivery.CHUNKED_FILE ? "CHUNKED" : "CONNECTION_CLOSE", true);
    }
  }

  private static void unknownErrors(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 1800, 1800)) {
      for (boolean wrongPath : new boolean[] {true, false}) {
        String path = wrongPath ? "/not-the-token/sample.mp4" : fixture.server.getPath();
        try (Client client = fixture.requestTarget(wrongPath ? "GET" : "POST", path,
            "Content-Length: 0\r\n", "HTTP/1.1")) {
          equal(wrongPath ? 404 : 405, client.status, "unknown-length mode error status");
          equal("0", client.header("content-length"), "errors explicitly declare an empty body");
          check(!client.headers.containsKey("transfer-encoding"), "errors are not chunk-framed");
          check(!client.headers.containsKey("content-range"), "errors have no media range");
          client.expectEof();
        }
      }
      emptySnapshot(fixture.server.getTransferSnapshot());
      equal(0, fixture.server.getRequestCount(), "errors do not count as successful asset requests");
      equal(0, fixture.server.getBytesServed(), "errors send no payload or framing");
    }
  }

  private static void unknownEofHold(StreamProbeHttpServer.Delivery delivery, boolean stop) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 180, stop ? 2500 : 800);
        Client client = fixture.request("GET", "")) {
      unknownHeaders(client, delivery);
      equalBytes(CONTENT, readUnknownPayload(client, delivery), "all payload precedes unknown-length EOF hold");
      StreamProbeHttpServer.TransferSnapshot held = fixture.snapshot(value -> value.payloadComplete
          && !value.responseComplete && value.activeGetCount == 1);
      equal(CONTENT.length, held.bytesSent, "EOF hold follows the complete payload");
      equal(CONTENT.length, held.plannedBytes, "EOF hold retains planned bytes");
      client.expectStillOpen(150);
      StreamProbeHttpServer.TransferSnapshot stillHeld = fixture.server.getTransferSnapshot();
      check(stillHeld.payloadComplete && !stillHeld.responseComplete,
          "payload completion and flush cannot mark a close-delimited response complete before EOF");
      check(fixture.history.stream().noneMatch(event -> event.transferId == held.id
          && event.phase == StreamProbeHttpServer.Phase.COMPLETE), "COMPLETE waits for EOF or abort");
      if (stop) {
        long stoppedAt = System.nanoTime();
        fixture.server.close();
        check(elapsed(stoppedAt) < 1800, "server stop interrupts unknown-length EOF hold");
        check(!fixture.server.isRunning(), "stopped endpoint reports not running");
        equal(0, client.readUntilClosedAllowReset().length, "stop adds no body or chunk terminator");
        StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, held.id);
        equal("stopped", complete.outcome, "canceled EOF hold has a stopped outcome");
        StreamProbeHttpServer.TransferSnapshot aborted = fixture.snapshot(value -> value.activeGetCount == 0);
        equal(held.id, aborted.id, "EOF cancellation retains the transfer identity");
        equal(CONTENT.length, aborted.bytesSent, "EOF cancellation follows all payload bytes");
        check(aborted.payloadComplete, "payload remains complete after EOF-hold cancellation");
        check(!aborted.responseComplete, "cancellation cannot mark successful response completion");
      } else {
        if (delivery == StreamProbeHttpServer.Delivery.CHUNKED_FILE) client.readChunkTerminator();
        client.expectEof();
        fixture.event(StreamProbeHttpServer.Phase.COMPLETE, held.id);
        completedSnapshot(fixture, held.id, CONTENT.length, 0);
      }
    }
  }

  private static void stopDuringClosePayload(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 2200, 800);
        Client client = fixture.request("GET", "")) {
      unknownHeaders(client, delivery);
      client.readFixed(1);
      StreamProbeHttpServer.TransferSnapshot partial = fixture.snapshot(value -> value.id > 0
          && value.bytesSent > 0 && value.bytesSent < value.plannedBytes);
      long stoppedAt = System.nanoTime();
      fixture.server.close();
      check(elapsed(stoppedAt) < 1800, "stop interrupts close-delimited payload pacing");
      client.readUntilClosedAllowReset();
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, partial.id);
      equal("stopped", complete.outcome, "canceled payload has a stopped outcome");
      StreamProbeHttpServer.TransferSnapshot aborted = fixture.snapshot(value -> value.activeGetCount == 0);
      equal(partial.id, aborted.id, "canceled close transfer identity");
      check(aborted.bytesSent < aborted.plannedBytes, "canceled close-delimited payload remains partial");
      check(!aborted.payloadComplete && !aborted.responseComplete,
          "socket closure cannot turn a truncated planned payload into successful completion");
    }
  }

  private static void http10Request() throws Exception {
    try (Fixture fixture = new Fixture(StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10, 120, 0);
        Client client = fixture.requestTarget("GET", fixture.server.getPath(), "", "HTTP/1.0")) {
      unknownHeaders(client, StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10);
      equalBytes(CONTENT, client.readFixed(CONTENT.length), "HTTP/1.0 request receives exact raw payload");
      client.expectEof();
      StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
      completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
      transportMetadata(complete, fixture.server.getTransferSnapshot(),
          "HTTP/1.0", "HTTP/1.0", "CONNECTION_CLOSE", false);
    }
  }

  private static void http10ChunkFallback(StreamProbeHttpServer.Delivery delivery) throws Exception {
    try (Fixture fixture = new Fixture(delivery, 400, 0)) {
      try (Client head = fixture.requestTarget("HEAD", fixture.server.getPath(),
          "Range: bytes=7-30\r\n", "HTTP/1.0")) {
        closeHeaders(head, "HTTP/1.0");
        head.expectEof();
      }
      StreamProbeHttpServer.Event head = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, 0);
      equal("HEAD", head.method, "HTTP/1.0 fallback HEAD event");
      equal("HTTP/1.0", head.requestProtocol, "HEAD fallback records request protocol");
      equal("HTTP/1.0", head.responseProtocol, "HEAD fallback records response protocol");
      equal("CONNECTION_CLOSE", head.framing, "HEAD fallback records actual framing");
      check(head.rangeRequested, "HEAD fallback records ignored Range");
      emptySnapshot(fixture.server.getTransferSnapshot());
      try (Client client = fixture.requestTarget("GET", fixture.server.getPath(),
          "Range: bytes=7-30\r\n", "HTTP/1.0")) {
        closeHeaders(client, "HTTP/1.0");
        StreamProbeHttpServer.Event started = fixture.event(StreamProbeHttpServer.Phase.STARTED, 0);
        event(started, StreamProbeHttpServer.Phase.STARTED, 200, 0, 0, CONTENT.length - 1);
        equal("http10_fallback", started.outcome, "fallback STARTED has a safe fixed outcome");
        transportMetadata(started, fixture.server.getTransferSnapshot(),
            "HTTP/1.0", "HTTP/1.0", "CONNECTION_CLOSE", true);
        equalBytes(CONTENT, client.readFixed(CONTENT.length), "HTTP/1.0 fallback body is raw full asset");
        client.expectEof();
        StreamProbeHttpServer.Event complete = fixture.event(StreamProbeHttpServer.Phase.COMPLETE, started.transferId);
        equal("served_http10_fallback", complete.outcome, "fallback COMPLETE has a safe fixed outcome");
        completedSnapshot(fixture, complete.transferId, CONTENT.length, 0);
        transportMetadata(complete, fixture.server.getTransferSnapshot(),
            "HTTP/1.0", "HTTP/1.0", "CONNECTION_CLOSE", true);
      }
      equal(2, fixture.server.getRequestCount(), "fallback HEAD plus GET request count");
      equal(CONTENT.length, fixture.server.getBytesServed(), "fallback sends raw payload bytes once");
    }
  }

  private static void transportMetadata(StreamProbeHttpServer.Event event,
      StreamProbeHttpServer.TransferSnapshot snapshot, String requestProtocol,
      String responseProtocol, String framing, boolean rangeRequested) {
    equal(requestProtocol, event.requestProtocol, "event request protocol");
    equal(responseProtocol, event.responseProtocol, "event response protocol");
    equal(framing, event.framing, "event actual response framing");
    check(event.rangeRequested == rangeRequested, "event records whether Range was requested");
    equal(requestProtocol, snapshot.requestProtocol, "snapshot request protocol");
    equal(responseProtocol, snapshot.responseProtocol, "snapshot response protocol");
    equal(framing, snapshot.framing, "snapshot actual response framing");
    check(snapshot.rangeRequested == rangeRequested, "snapshot records whether Range was requested");
  }

  private static byte[] readUnknownPayload(Client client, StreamProbeHttpServer.Delivery delivery)
      throws IOException {
    return delivery == StreamProbeHttpServer.Delivery.CHUNKED_FILE
        ? client.readChunkedPayload(CONTENT.length) : client.readFixed(CONTENT.length);
  }

  private static void unknownHeaders(Client client, StreamProbeHttpServer.Delivery delivery) {
    equal(delivery == StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10 ? "HTTP/1.0" : "HTTP/1.1",
        client.protocol, "delivery response protocol");
    if (delivery == StreamProbeHttpServer.Delivery.CHUNKED_FILE) {
      chunkedHeaders(client);
      return;
    }
    closeHeaders(client, delivery == StreamProbeHttpServer.Delivery.PACED_CLOSE_HTTP10
        ? "HTTP/1.0" : "HTTP/1.1");
  }

  private static void closeHeaders(Client client, String protocol) {
    equal(protocol, client.protocol, "raw close-delimited response protocol");
    equal(200, client.status, "close-delimited response ignores Range with full 200");
    equal("video/mp4", client.header("content-type"), "close-delimited MP4 type");
    equal("none", client.header("accept-ranges"), "close-delimited Accept-Ranges");
    equal("close", client.header("connection").toLowerCase(Locale.ROOT), "close-delimited connection policy");
    check(!client.headers.containsKey("content-length"), "close-delimited success has no Content-Length");
    check(!client.headers.containsKey("transfer-encoding"), "close-delimited success has no Transfer-Encoding");
    check(!client.headers.containsKey("content-range"), "close-delimited success has no Content-Range");
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
      return requestTarget(method, server.getPath(), headers, "HTTP/1.1");
    }

    Client requestTarget(String method, String target, String headers, String protocol) throws IOException {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, server.getPort()), 2000);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        String text = method + " " + target + " " + protocol + "\r\nHost: localhost\r\n"
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
    final String protocol;
    final int status;
    final Map<String, String> headers = new LinkedHashMap<>();

    Client(Socket socket) throws IOException {
      this.socket = socket;
      input = socket.getInputStream();
      String[] statusLine = readLine().split(" +", 3);
      check(statusLine.length >= 2 && statusLine[0].startsWith("HTTP/1."), "HTTP status line");
      protocol = statusLine[0];
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
