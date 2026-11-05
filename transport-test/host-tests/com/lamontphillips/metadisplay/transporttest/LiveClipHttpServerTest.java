package com.lamontphillips.metadisplay.transporttest;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Real loopback socket checks; all body bytes are deterministic synthetic test data. */
public final class LiveClipHttpServerTest {
  private static final int SLOTS = 4;
  private static final int SLOT_BYTES = 4096;
  private static final byte[] INIT = bytes(37, 11);
  private static final int TOTAL = INIT.length + SLOTS * SLOT_BYTES;

  private LiveClipHttpServerTest() {}

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    tests.add(new NamedCase("loopback-only binding", LiveClipHttpServerTest::loopbackOnly));
    tests.add(new NamedCase("lifecycle and live source counter", LiveClipHttpServerTest::lifecycle));
    tests.add(new NamedCase("full GET waits for production with exact length", LiveClipHttpServerTest::progressiveGet));
    tests.add(new NamedCase("HEAD advertises exact future length without waiting", LiveClipHttpServerTest::head));
    tests.add(new NamedCase("bounded range", () -> range("bytes=7-19", 7, 19)));
    tests.add(new NamedCase("open range", () -> range("bytes=16000-", 16000, TOTAL - 1)));
    tests.add(new NamedCase("suffix range", () -> range("bytes=-12", TOTAL - 12, TOTAL - 1)));
    tests.add(new NamedCase("range clamps end", () -> range("bytes=16000-999999", 16000, TOTAL - 1)));
    tests.add(new NamedCase("future range waits for producer", LiveClipHttpServerTest::futureRange));
    tests.add(new NamedCase("HEAD range does not wait for future bytes", LiveClipHttpServerTest::headRange));
    for (String value : new String[] {"bytes=16421-", "bytes=9-8", "bytes=1-2,3-4",
        "bytes=0-9223372036854775808", "bytes=-0", "items=0-1"}) {
      tests.add(new NamedCase("reject invalid range " + value, () -> invalidRange(value)));
    }
    tests.add(new NamedCase("HTTP/1.0 response has length without chunking", LiveClipHttpServerTest::http10));
    tests.add(new NamedCase("wrong path returns 404 before source init", LiveClipHttpServerTest::wrongPath));
    tests.add(new NamedCase("unsupported method is safe 405", LiveClipHttpServerTest::unsupportedMethod));
    tests.add(new NamedCase("reject request body framing", LiveClipHttpServerTest::requestBody));
    tests.add(new NamedCase("overlong request line is bounded", LiveClipHttpServerTest::longRequest));
    tests.add(new NamedCase("uninitialized source has bounded 503", LiveClipHttpServerTest::uninitialized));
    tests.add(new NamedCase("failed source before headers is 503", LiveClipHttpServerTest::failedBeforeHeaders));
    tests.add(new NamedCase("producer failure after headers closes incomplete body", LiveClipHttpServerTest::failedAfterHeaders));
    tests.add(new NamedCase("stop wakes production waits without owning source", LiveClipHttpServerTest::stopWaiting));
    tests.add(new NamedCase("stop interrupts a non-reading client write", LiveClipHttpServerTest::stopBlockedWrite));
    tests.add(new NamedCase("latest GET owns concurrent snapshot", LiveClipHttpServerTest::overlappingGets));
    tests.add(new NamedCase("HEAD does not replace GET snapshot", LiveClipHttpServerTest::headPreserves));
    tests.add(new NamedCase("throwing listener does not interrupt transfer", LiveClipHttpServerTest::throwingListener));
    tests.add(new NamedCase("worker callback can stop; external close confirms joins", LiveClipHttpServerTest::closeFromCallback));
    tests.add(new NamedCase("legacy constructor retains 75-second response budget", LiveClipHttpServerTest::defaultDeadline));
    tests.add(new NamedCase("selected response deadlines are validated", LiveClipHttpServerTest::deadlineValidation));
    tests.add(new NamedCase("short whole-response deadline closes a waiting body", LiveClipHttpServerTest::waitingDeadline));
    tests.add(new NamedCase("whole-response deadline does not reset on ongoing production", LiveClipHttpServerTest::progressingDeadline));
    tests.add(new NamedCase("one-minute quota streams sixty exact future slots", LiveClipHttpServerTest::minuteGet));
    tests.add(new NamedCase("one-minute quota supports a cross-slot range", LiveClipHttpServerTest::minuteRange));
    tests.add(new NamedCase("one-minute quota waits for a future tail range", LiveClipHttpServerTest::minuteFutureTail));
    tests.add(new NamedCase("one-minute waiting source remains cancelable", LiveClipHttpServerTest::minuteStop));
    tests.add(new NamedCase("five-minute quota streams300 exact slots with matching hash", LiveClipHttpServerTest::fiveMinuteGet));
    tests.add(new NamedCase("five-minute range crosses the final slot boundary", LiveClipHttpServerTest::fiveMinuteRange));
    tests.add(new NamedCase("five-minute tail range waits for future production", LiveClipHttpServerTest::fiveMinuteFutureTail));
    tests.add(new NamedCase("five-minute future range remains promptly cancelable", LiveClipHttpServerTest::fiveMinuteStop));
    int failed = 0;
    for (NamedCase test : tests) {
      try {
        test.body.run();
        System.out.println("PASS " + test.name);
      } catch (Throwable failure) {
        failed++;
        System.err.println("FAIL " + test.name + ": " + failure);
        failure.printStackTrace(System.err);
      }
    }
    System.out.println("LiveClipHttpServerTest: " + (tests.size() - failed) + "/"
        + tests.size() + " passed; " + failed + " failed");
    if (failed != 0) System.exit(1);
  }

  private static void loopbackOnly() throws Exception {
    try (BoundedMediaBuffer source = source(false)) {
      try {
        new LiveClipHttpServer(source, InetAddress.getByAddress(new byte[] {0, 0, 0, 0}), e -> {});
        throw new AssertionError("wildcard accepted");
      } catch (IllegalArgumentException expected) { }
    }
  }

  private static void lifecycle() throws Exception {
    try (Fixture f = new Fixture(false)) {
      check(f.server.isRunning(), "running");
      check(f.server.getPath().matches("/[0-9a-f]{32}/sample\\.mp4"), "128-bit token path");
      equal(0, f.server.getTransferSnapshot().id, "no GET");
      equal(INIT.length, f.server.getTransferSnapshot().sourceBytesProduced, "initial production sampled");
      f.source.onFragment(bytes(SLOT_BYTES, 1));
      equal(INIT.length + SLOT_BYTES, f.server.getTransferSnapshot().sourceBytesProduced,
          "source production is sampled without a socket write");
      f.server.close();
      f.server.close();
      check(!f.server.isRunning(), "closed");
      check(!f.source.isClosed(), "server does not own source");
      try { f.server.start(); throw new AssertionError("closed server restarted"); }
      catch (IOException expected) { }
    }
  }

  private static void progressiveGet() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("GET", "")) {
      headers(c, 200, TOTAL);
      equalBytes(INIT, c.readFixed(INIT.length), "initialization delivered first");
      LiveClipHttpServer.TransferSnapshot first = f.snapshot(s -> s.bytesSent == INIT.length);
      check(!first.payloadComplete && !first.responseComplete && !first.writeInProgress, "partial flags");
      equal(TOTAL, first.plannedBytes, "honest planned bytes");
      equal(INIT.length, first.sourceBytesProduced, "only init produced");
      equal(1, first.activeGetCount, "one active GET");
      c.expectWaiting();
      appendSlots(f.source);
      equalBytes(Arrays.copyOfRange(allBytes(), INIT.length, TOTAL),
          c.readFixed(TOTAL - INIT.length), "all future slots delivered exactly");
      c.expectEof();
      LiveClipHttpServer.TransferSnapshot done = f.snapshot(s -> s.responseComplete && s.activeGetCount == 0);
      equal(TOTAL, done.bytesSent, "full body counter");
      check(done.payloadComplete, "payload complete");
      equal(TOTAL, done.sourceBytesProduced, "full source counter");
      equal(1, f.server.getRequestCount(), "request count");
      equal(TOTAL, f.server.getBytesServed(), "body count");
    }
  }

  private static void head() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("HEAD", "")) {
      headers(c, 200, TOTAL);
      c.expectEof();
      equal(0, f.server.getTransferSnapshot().id, "HEAD has no GET snapshot");
      equal(0, f.server.getBytesServed(), "HEAD sends no body");
      check(!f.source.isComplete(), "HEAD did not require source completion");
    }
  }

  private static void range(String value, int first, int last) throws Exception {
    try (Fixture f = new Fixture(true); Client c = f.request("GET", "Range: " + value + "\r\n")) {
      headers(c, 206, last - first + 1);
      equal("bytes " + first + "-" + last + "/" + TOTAL, c.header("content-range"), "range header");
      equalBytes(Arrays.copyOfRange(allBytes(), first, last + 1), c.readFixed(last - first + 1), "range bytes");
      c.expectEof();
      LiveClipHttpServer.TransferSnapshot s = f.snapshot(v -> v.responseComplete);
      equal(first, s.start, "range start");
      check(s.rangeRequested, "range metadata");
    }
  }

  private static void futureRange() throws Exception {
    int start = INIT.length + SLOT_BYTES;
    try (Fixture f = new Fixture(false); Client c = f.request("GET", "Range: bytes=" + start + "-" + (start + 9) + "\r\n")) {
      headers(c, 206, 10);
      c.expectWaiting();
      f.snapshot(s -> s.activeGetCount == 1 && s.bytesSent == 0);
      f.source.onFragment(bytes(SLOT_BYTES, 1));
      // Exercise a wait timeout while production advances before the requested offset.
      Thread.sleep(550);
      c.expectWaiting();
      for (int i = 1; i < SLOTS; i++) f.source.onFragment(bytes(SLOT_BYTES, i + 1));
      f.source.onComplete();
      equalBytes(Arrays.copyOfRange(allBytes(), start, start + 10), c.readFixed(10), "future range body");
      c.expectEof();
    }
  }

  private static void headRange() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("HEAD", "Range: bytes=-9\r\n")) {
      headers(c, 206, 9);
      c.expectEof();
      equal(0, f.server.getBytesServed(), "range HEAD no body");
    }
  }

  private static void invalidRange(String value) throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("GET", "Range: " + value + "\r\n")) {
      equal(416, c.status, "invalid status");
      equal("bytes */" + TOTAL, c.header("content-range"), "unsatisfied range");
      equal("0", c.header("content-length"), "empty error body");
      c.expectEof();
      equal(0, f.server.getTransferSnapshot().id, "no successful GET snapshot");
    }
  }

  private static void http10() throws Exception {
    try (Fixture f = new Fixture(true); Client c = f.raw("GET " + f.server.getPath() + " HTTP/1.0\r\n\r\n")) {
      headers(c, 200, TOTAL);
      equal("HTTP/1.0", c.protocol, "matching version");
      equalBytes(allBytes(), c.readFixed(TOTAL), "HTTP1.0 body");
      c.expectEof();
      LiveClipHttpServer.TransferSnapshot s = f.snapshot(v -> v.responseComplete);
      equal("HTTP/1.0", s.requestProtocol, "request metadata");
      equal("HTTP/1.0", s.responseProtocol, "response metadata");
      equal("CONTENT_LENGTH", s.framing, "truthful framing");
    }
  }

  private static void wrongPath() throws Exception {
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(SLOTS, SLOT_BYTES);
        LiveClipHttpServer server = server(source, e -> {})) {
      server.start();
      try (Client c = new Client(server, "GET /wrong/sample.mp4 HTTP/1.1\r\n\r\n")) {
        equal(404, c.status, "wrong path");
        c.expectEof();
      }
    }
  }

  private static void unsupportedMethod() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("POST", "")) {
      equal(405, c.status, "method status");
      equal("GET, HEAD", c.header("allow"), "allowed methods");
      c.expectEof();
    }
  }

  private static void requestBody() throws Exception {
    for (String header : new String[] {"Content-Length: 1\r\n", "Transfer-Encoding: chunked\r\n",
        "Content-Length: 0\r\nContent-Length: 0\r\n"}) {
      try (Fixture f = new Fixture(false); Client c = f.request("GET", header)) {
        equal(400, c.status, "request body rejected");
        c.expectEof();
      }
    }
  }

  private static void longRequest() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.raw("GET /" + "a".repeat(5000) + " HTTP/1.1\r\n\r\n")) {
      equal(414, c.status, "bounded request line");
      c.expectEof();
    }
  }

  private static void uninitialized() throws Exception {
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(SLOTS, SLOT_BYTES);
        LiveClipHttpServer server = server(source, e -> {})) {
      server.start();
      long begun = System.nanoTime();
      try (Client c = new Client(server, "GET " + server.getPath() + " HTTP/1.1\r\n\r\n")) {
        equal(503, c.status, "bounded source wait status");
        c.expectEof();
      }
      check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 6500, "bounded init wait");
    }
  }

  private static void failedBeforeHeaders() throws Exception {
    try (Fixture f = new Fixture(false)) {
      f.source.fail("ENCODER_FAILED");
      try (Client c = f.request("GET", "")) {
        equal(503, c.status, "failed source status");
        c.expectEof();
      }
    }
  }

  private static void failedAfterHeaders() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("GET", "")) {
      headers(c, 200, TOTAL);
      equalBytes(INIT, c.readFixed(INIT.length), "initial bytes before failure");
      f.source.fail("ENCODER_FAILED");
      c.expectEof();
      LiveClipHttpServer.TransferSnapshot s = f.snapshot(v -> v.activeGetCount == 0);
      check(!s.payloadComplete && !s.responseComplete, "failure cannot look complete");
      equal(INIT.length, s.bytesSent, "no injected HTTP error headers in media body");
    }
  }

  private static void stopWaiting() throws Exception {
    try (Fixture f = new Fixture(false); Client c = f.request("GET", "")) {
      c.readFixed(INIT.length);
      long begun = System.nanoTime();
      f.server.close();
      check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 2500, "bounded stop");
      c.expectEof();
      check(!f.source.isClosed(), "source remains caller-owned");
      f.source.onFragment(bytes(SLOT_BYTES, 1));
      check(!f.server.getTransferSnapshot().responseComplete, "stopped transfer incomplete");
    }
  }

  private static void overlappingGets() throws Exception {
    try (Fixture f = new Fixture(false); Client first = f.request("GET", "")) {
      first.readFixed(INIT.length);
      long firstId = f.snapshot(s -> s.activeGetCount == 1).id;
      try (Client second = f.request("GET", "Range: bytes=" + INIT.length + "-" + (INIT.length + 9) + "\r\n")) {
        LiveClipHttpServer.TransferSnapshot newer = f.snapshot(s -> s.totalGetCount == 2);
        check(newer.id > firstId, "new GET owns snapshot");
        equal(2, newer.activeGetCount, "concurrent GET count");
        first.close();
        appendSlots(f.source);
        second.readFixed(10);
        second.expectEof();
        LiveClipHttpServer.TransferSnapshot done = f.snapshot(s -> s.activeGetCount == 0);
        equal(newer.id, done.id, "older GET cannot replace latest snapshot");
        equal(10, done.bytesSent, "latest GET byte count remains separate");
        equal(2, done.totalGetCount, "retained total count");
      }
    }
  }

  private static void stopBlockedWrite() throws Exception {
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(128, 65536);
        LiveClipHttpServer server = server(source, e -> {})) {
      source.onInitialization(INIT);
      byte[] slot = bytes(65536, 1);
      for (int i = 0; i < 128; i++) source.onFragment(slot);
      source.onComplete();
      server.start();
      try (Socket socket = new Socket()) {
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 1000);
        socket.getOutputStream().write(("GET " + server.getPath() + " HTTP/1.1\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
          LiveClipHttpServer.TransferSnapshot snapshot = server.getTransferSnapshot();
          if (snapshot.writeInProgress) {
            Thread.sleep(100);
            if (server.getTransferSnapshot().writeInProgress) { blocked = true; break; }
          }
          Thread.sleep(5);
        }
        check(blocked, "client backpressure reached a guarded socket write");
        long begun = System.nanoTime();
        server.close();
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 2500,
            "closing socket interrupts blocked write and confirms workers stopped");
        LiveClipHttpServer.TransferSnapshot stopped = server.getTransferSnapshot();
        equal(0, stopped.activeGetCount, "no retained worker");
        check(!stopped.responseComplete && !stopped.writeInProgress, "aborted write remains incomplete");
      }
    }
  }

  private static void headPreserves() throws Exception {
    try (Fixture f = new Fixture(false); Client get = f.request("GET", "")) {
      get.readFixed(INIT.length);
      long id = f.snapshot(s -> s.activeGetCount == 1).id;
      try (Client head = f.request("HEAD", "")) { head.expectEof(); }
      LiveClipHttpServer.TransferSnapshot s = f.server.getTransferSnapshot();
      equal(id, s.id, "HEAD does not replace snapshot");
      equal(1, s.totalGetCount, "HEAD is not a GET");
      equal(2, f.server.getRequestCount(), "both asset requests counted");
    }
  }

  private static void throwingListener() throws Exception {
    try (BoundedMediaBuffer source = source(true);
        LiveClipHttpServer server = server(source, e -> { throw new IllegalStateException("test"); })) {
      server.start();
      try (Client c = new Client(server, "GET " + server.getPath() + " HTTP/1.1\r\n\r\n")) {
        equalBytes(allBytes(), c.readFixed(TOTAL), "listener cannot interrupt body");
        c.expectEof();
      }
    }
  }

  private static void closeFromCallback() throws Exception {
    AtomicReference<LiveClipHttpServer> owner = new AtomicReference<>();
    try (BoundedMediaBuffer source = source(false);
        LiveClipHttpServer server = server(source, e -> {
          if (e.phase == LiveClipHttpServer.Phase.STARTED) owner.get().close();
        })) {
      owner.set(server);
      server.start();
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 1000);
        socket.setSoTimeout(4000);
        socket.getOutputStream().write(("GET " + server.getPath() + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        try { equal(-1, socket.getInputStream().read(), "callback stop closes connection"); }
        catch (java.net.SocketException expected) { }
      }
      server.close();
      check(!server.isRunning(), "callback/external stop confirmed");
    }
  }

  private static void defaultDeadline() throws Exception {
    try (Fixture fixture = new Fixture(false)) {
      equal(LiveSessionPlan.SHORT.getHttpResponseLimitMs(), fixture.server.getResponseLimitMs(),
          "legacy constructor uses the baseline response deadline");
      try (Client head = fixture.request("HEAD", "")) {
        headers(head, 200, TOTAL);
        head.expectEof();
      }
    }
  }

  private static void deadlineValidation() throws Exception {
    try (BoundedMediaBuffer source = source(false)) {
      InetAddress address = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      for (long invalid : new long[] {Long.MIN_VALUE, -1, 0, 900001, Long.MAX_VALUE}) {
        try {
          new LiveClipHttpServer(source, address, invalid, event -> {});
          throw new AssertionError("invalid deadline accepted: " + invalid);
        } catch (IllegalArgumentException expected) { }
      }
      for (long accepted : new long[] {1, 75000, 150000, 180000, 660000, 900000}) {
        try (LiveClipHttpServer server = new LiveClipHttpServer(source, address, accepted, event -> {})) {
          equal(accepted, server.getResponseLimitMs(), "selected bounded deadline retained");
        }
      }
    }
  }

  private static void waitingDeadline() throws Exception {
    BlockingQueue<LiveClipHttpServer.Event> events = new LinkedBlockingQueue<>();
    try (BoundedMediaBuffer source = source(false);
        LiveClipHttpServer server = new LiveClipHttpServer(source,
            InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 120, events::add)) {
      server.start();
      long begun = System.nanoTime();
      try (Client client = request(server, "GET", "")) {
        headers(client, 200, TOTAL);
        equalBytes(INIT, client.readFixed(INIT.length), "init before finite deadline");
        client.expectEof();
      }
      check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 1800,
          "selected short deadline must close instead of waiting for the 75-second baseline");
      LiveClipHttpServer.Event complete = completeEvent(events);
      equal("source_timeout", complete.outcome, "deadline safe outcome");
      equal(INIT.length, complete.bytesSent, "partial response byte count");
      LiveClipHttpServer.TransferSnapshot snapshot = server.getTransferSnapshot();
      check(!snapshot.payloadComplete && !snapshot.responseComplete, "deadline cannot appear complete");
      equal(0, snapshot.activeGetCount, "deadline releases the worker");
      check(!source.isClosed(), "HTTP deadline does not own the source");
    }
  }

  private static void progressingDeadline() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    BlockingQueue<LiveClipHttpServer.Event> events = new LinkedBlockingQueue<>();
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = new LiveClipHttpServer(source,
            InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 220, events::add)) {
      source.onInitialization(INIT);
      server.start();
      try (Client client = request(server, "GET", "");
          SyntheticProducer producer = new SyntheticProducer(source, 60, 65536, 30)) {
        equal(INIT.length + plan.getSlotPayloadBytes(), Long.parseLong(client.header("content-length")),
            "honest full length while a shorter response deadline is tested");
        long begun = System.nanoTime();
        producer.start();
        byte[] partial = client.input.readAllBytes();
        check(partial.length > INIT.length, "production kept advancing during response");
        check(partial.length < INIT.length + plan.getSlotPayloadBytes(), "body stopped before full production");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 1800,
            "progress must not extend the whole-response budget");
        LiveClipHttpServer.Event complete = completeEvent(events);
        equal("source_timeout", complete.outcome, "absolute deadline under progress");
        check(!server.getTransferSnapshot().responseComplete, "advancing source still aborted incomplete");
      }
    }
  }

  private static void minuteGet() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    int total = Math.toIntExact(INIT.length + plan.getSlotPayloadBytes());
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = minuteServer(source)) {
      source.onInitialization(INIT);
      server.start();
      try (Client client = request(server, "GET", "");
          SyntheticProducer producer = new SyntheticProducer(source, plan.getFragmentCount(), plan.getSlotBytes(), 2)) {
        headers(client, 200, total);
        equal(150000, server.getResponseLimitMs(), "one-minute response deadline selected");
        equalBytes(INIT, client.readFixed(INIT.length), "init arrives before sixty future slots");
        client.expectWaiting();
        producer.start();
        byte[] body = client.readFixed(total - INIT.length);
        client.expectEof();
        producer.awaitSuccess();
        for (int slot = 0; slot < plan.getFragmentCount(); slot++) {
          equalBytes(bytes(plan.getSlotBytes(), slot + 1),
              Arrays.copyOfRange(body, slot * plan.getSlotBytes(), (slot + 1) * plan.getSlotBytes()),
              "exact sixty-slot payload at " + slot);
        }
        LiveClipHttpServer.TransferSnapshot done = awaitSnapshot(server,
            value -> value.responseComplete && value.activeGetCount == 0);
        equal(total, done.bytesSent, "one-minute exact transmitted length");
        equal(total, done.sourceBytesProduced, "one-minute full production sampled");
        equal(60, source.getCompletedSlots(), "sixty slots complete");
        check(source.isComplete() && done.payloadComplete, "one continuous planned response completed");
      }
    }
  }

  private static void minuteRange() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = minuteServer(source)) {
      source.onInitialization(INIT);
      for (int slot = 0; slot < plan.getFragmentCount(); slot++) source.onFragment(bytes(plan.getSlotBytes(), slot + 1));
      source.onComplete();
      server.start();
      int start = INIT.length + 58 * plan.getSlotBytes() + plan.getSlotBytes() - 9;
      int end = start + 25;
      try (Client client = request(server, "GET", "Range: bytes=" + start + "-" + end + "\r\n")) {
        headers(client, 206, end - start + 1);
        equal("bytes " + start + "-" + end + "/" + source.getContentLength(),
            client.header("content-range"), "cross-slot range retains the full truthful size");
        byte[] expected = new byte[26];
        for (int i = 0; i < expected.length; i++) expected[i] = minuteByte(start + i, plan.getSlotBytes());
        equalBytes(expected, client.readFixed(expected.length), "range crosses slots59/60 exactly");
        client.expectEof();
      }
    }
  }

  private static void minuteFutureTail() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = minuteServer(source)) {
      source.onInitialization(INIT);
      server.start();
      int total = Math.toIntExact(source.getContentLength());
      try (Client client = request(server, "GET", "Range: bytes=" + (total - 17) + "-\r\n");
          SyntheticProducer producer = new SyntheticProducer(source, plan.getFragmentCount(), plan.getSlotBytes(), 2)) {
        headers(client, 206, 17);
        client.expectWaiting();
        producer.start();
        byte[] expected = new byte[17];
        for (int i = 0; i < expected.length; i++) expected[i] = minuteByte(total - 17 + i, plan.getSlotBytes());
        equalBytes(expected, client.readFixed(17), "future tail waited for all sixty production slots");
        client.expectEof();
        producer.awaitSuccess();
        LiveClipHttpServer.TransferSnapshot done = awaitSnapshot(server, value -> value.responseComplete);
        equal(17, done.bytesSent, "tail byte count is not the entire media length");
        equal(total, done.sourceBytesProduced, "source retains actual production separately");
      }
    }
  }

  private static void minuteStop() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.ONE_MINUTE;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = minuteServer(source)) {
      source.onInitialization(INIT);
      server.start();
      try (Client client = request(server, "GET", "")) {
        client.readFixed(INIT.length);
        long begun = System.nanoTime();
        server.close();
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 2500,
            "selected150-second budget does not lengthen Stop");
        client.expectEof();
        check(!source.isClosed() && !server.getTransferSnapshot().responseComplete,
            "stopped source remains caller-owned and incomplete");
      }
    }
  }

  private static void fiveMinuteGet() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    int total = Math.toIntExact(INIT.length + plan.getSlotPayloadBytes());
    MessageDigest expected = MessageDigest.getInstance("SHA-256");
    MessageDigest actual = MessageDigest.getInstance("SHA-256");
    expected.update(INIT);
    for (int i = 0; i < plan.getFragmentCount(); i++) expected.update(wideSlot(plan.getSlotBytes(), i + 1));
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = plannedServer(source, plan)) {
      source.onInitialization(INIT);
      server.start();
      try (Client client = request(server, "GET", "");
          SyntheticProducer producer = new SyntheticProducer(source, plan.getFragmentCount(), plan.getSlotBytes(), 1, true)) {
        headers(client, 200, total);
        equal(660000, server.getResponseLimitMs(), "five-minute response deadline selected");
        byte[] initial = client.readFixed(INIT.length);
        equalBytes(INIT, initial, "five-minute initialization arrives before capture completes");
        actual.update(initial);
        client.expectWaiting();
        producer.start();
        byte[] chunk = new byte[16384];
        int remaining = total - initial.length;
        while (remaining > 0) {
          int amount = client.input.read(chunk, 0, Math.min(chunk.length, remaining));
          check(amount > 0, "five-minute transfer reaches every promised byte");
          actual.update(chunk, 0, amount);
          remaining -= amount;
        }
        client.expectEof();
        producer.awaitSuccess();
        check(Arrays.equals(expected.digest(), actual.digest()), "300-slot body hash matches exact produced bytes");
        LiveClipHttpServer.TransferSnapshot done = awaitSnapshot(server,
            value -> value.responseComplete && value.activeGetCount == 0);
        equal(total, done.bytesSent, "five-minute exact transmission length");
        equal(total, done.sourceBytesProduced, "five-minute source byte count");
        equal(300, source.getCompletedSlots(), "all300 planned slots complete");
        equal(1, done.totalGetCount, "one continuous response, no clip rollover");
        check(done.payloadComplete && source.isComplete(), "full five-minute budget completed");
      }
    }
  }

  private static void fiveMinuteRange() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = plannedServer(source, plan)) {
      source.onInitialization(INIT);
      for (int i = 0; i < plan.getFragmentCount(); i++) source.onFragment(wideSlot(plan.getSlotBytes(), i + 1));
      source.onComplete();
      server.start();
      int start = INIT.length + 299 * plan.getSlotBytes() - 11;
      int end = start + 32;
      try (Client client = request(server, "GET", "Range: bytes=" + start + "-" + end + "\r\n")) {
        headers(client, 206, end - start + 1);
        equal("bytes " + start + "-" + end + "/" + source.getContentLength(),
            client.header("content-range"), "range keeps actual300-slot full length");
        byte[] expected = new byte[end - start + 1];
        for (int i = 0; i < expected.length; i++) expected[i] = fiveMinuteByte(start + i, plan.getSlotBytes());
        equalBytes(expected, client.readFixed(expected.length), "slots299/300 boundary bytes intact");
        client.expectEof();
      }
      int last = Math.toIntExact(source.getContentLength() - 1);
      try (Client client = request(server, "GET", "Range: bytes=" + last + "-99999999\r\n")) {
        headers(client, 206, 1);
        equal(fiveMinuteByte(last, plan.getSlotBytes()) & 255, client.readFixed(1)[0] & 255,
            "last-byte range clamps without exceeding truthful length");
        client.expectEof();
      }
    }
  }

  private static void fiveMinuteFutureTail() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = plannedServer(source, plan)) {
      source.onInitialization(INIT);
      server.start();
      int total = Math.toIntExact(source.getContentLength());
      try (Client client = request(server, "GET", "Range: bytes=" + (total - 31) + "-\r\n");
          SyntheticProducer producer = new SyntheticProducer(source, plan.getFragmentCount(), plan.getSlotBytes(), 2, true)) {
        headers(client, 206, 31);
        client.expectWaiting();
        producer.start();
        byte[] expected = new byte[31];
        for (int i = 0; i < expected.length; i++) expected[i] = fiveMinuteByte(total - 31 + i, plan.getSlotBytes());
        equalBytes(expected, client.readFixed(31), "tail waits through300 progressing slots");
        client.expectEof();
        producer.awaitSuccess();
        LiveClipHttpServer.TransferSnapshot done = awaitSnapshot(server, value -> value.responseComplete);
        equal(31, done.bytesSent, "range count remains distinct from full source size");
        equal(total, done.sourceBytesProduced, "future tail completed after the full source was produced");
      }
    }
  }

  private static void fiveMinuteStop() throws Exception {
    LiveSessionPlan plan = LiveSessionPlan.FIVE_MINUTES;
    try (BoundedMediaBuffer source = new BoundedMediaBuffer(plan.getFragmentCount(), plan.getSlotBytes());
        LiveClipHttpServer server = plannedServer(source, plan)) {
      source.onInitialization(INIT);
      server.start();
      long start = source.getContentLength() - 7;
      try (Client client = request(server, "GET", "Range: bytes=" + start + "-\r\n")) {
        headers(client, 206, 7);
        client.expectWaiting();
        long begun = System.nanoTime();
        server.close();
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < 2500,
            "660-second budget does not lengthen Stop or wait for unproduced tail");
        client.expectEof();
        LiveClipHttpServer.TransferSnapshot stopped = server.getTransferSnapshot();
        check(!stopped.responseComplete && !stopped.payloadComplete, "canceled five-minute body is incomplete");
        equal(0, stopped.activeGetCount, "cancel confirms workers stopped");
        check(!source.isClosed(), "server cancellation does not own source cache");
      }
    }
  }

  private static byte minuteByte(int offset, int slotBytes) {
    if (offset < INIT.length) return INIT[offset];
    int position = offset - INIT.length;
    return (byte) ((position % slotBytes) * 31 + position / slotBytes + 1);
  }

  /** Include the full slot index so positions beyond slot256 cannot alias earlier data. */
  private static byte[] wideSlot(int size, int seed) {
    byte[] result = bytes(size, seed);
    for (int i = 0; i < 4; i++) result[i] = (byte) (seed >>> (24 - i * 8));
    return result;
  }

  private static byte fiveMinuteByte(int offset, int slotBytes) {
    if (offset < INIT.length) return INIT[offset];
    int position = offset - INIT.length;
    int withinSlot = position % slotBytes;
    int seed = position / slotBytes + 1;
    return withinSlot < 4 ? (byte) (seed >>> (24 - withinSlot * 8))
        : (byte) (withinSlot * 31 + seed);
  }

  private static Client request(LiveClipHttpServer server, String method, String extraHeaders) throws Exception {
    return new Client(server, method + " " + server.getPath() + " HTTP/1.1\r\n" + extraHeaders + "\r\n");
  }

  private static LiveClipHttpServer minuteServer(BoundedMediaBuffer source) throws Exception {
    return plannedServer(source, LiveSessionPlan.ONE_MINUTE);
  }

  private static LiveClipHttpServer plannedServer(BoundedMediaBuffer source, LiveSessionPlan plan)
      throws Exception {
    return new LiveClipHttpServer(source, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
        plan.getHttpResponseLimitMs(), event -> {});
  }

  private static LiveClipHttpServer.TransferSnapshot awaitSnapshot(LiveClipHttpServer server,
      Predicate<LiveClipHttpServer.TransferSnapshot> predicate) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      LiveClipHttpServer.TransferSnapshot value = server.getTransferSnapshot();
      if (predicate.test(value)) return value;
      Thread.sleep(5);
    }
    throw new AssertionError("snapshot condition not observed");
  }

  private static LiveClipHttpServer.Event completeEvent(BlockingQueue<LiveClipHttpServer.Event> events)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (System.nanoTime() < deadline) {
      LiveClipHttpServer.Event event = events.poll(100, TimeUnit.MILLISECONDS);
      if (event != null && event.phase == LiveClipHttpServer.Phase.COMPLETE) return event;
    }
    throw new AssertionError("terminal response event missing");
  }

  private static final class SyntheticProducer implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread thread;
    SyntheticProducer(BoundedMediaBuffer source, int slots, int slotBytes, long cadenceMs) {
      this(source, slots, slotBytes, cadenceMs, false);
    }
    SyntheticProducer(BoundedMediaBuffer source, int slots, int slotBytes, long cadenceMs,
        boolean wideSeeds) {
      thread = new Thread(() -> {
        try {
          for (int slot = 0; slot < slots && running.get(); slot++) {
            Thread.sleep(cadenceMs);
            if (!running.get()) return;
            source.onFragment(wideSeeds ? wideSlot(slotBytes, slot + 1) : bytes(slotBytes, slot + 1));
          }
          if (running.get()) source.onComplete();
        } catch (Throwable problem) {
          if (running.get()) failure.set(problem);
        }
      }, "minute-synthetic-producer");
    }
    void start() { thread.start(); }
    void awaitSuccess() throws Exception {
      thread.join(4000);
      check(!thread.isAlive(), "fast synthetic producer finished");
      if (failure.get() != null) throw new AssertionError("synthetic producer failed", failure.get());
    }
    @Override public void close() throws Exception {
      running.set(false);
      thread.interrupt();
      thread.join(2000);
      check(!thread.isAlive(), "synthetic producer cleanup bounded");
    }
  }

  private static BoundedMediaBuffer source(boolean complete) throws Exception {
    BoundedMediaBuffer source = new BoundedMediaBuffer(SLOTS, SLOT_BYTES);
    source.onInitialization(INIT);
    if (complete) appendSlots(source);
    return source;
  }

  private static void appendSlots(BoundedMediaBuffer source) throws Exception {
    for (int i = 0; i < SLOTS; i++) source.onFragment(bytes(SLOT_BYTES, i + 1));
    source.onComplete();
  }

  private static byte[] allBytes() {
    byte[] all = new byte[TOTAL];
    System.arraycopy(INIT, 0, all, 0, INIT.length);
    for (int i = 0; i < SLOTS; i++) System.arraycopy(bytes(SLOT_BYTES, i + 1), 0, all,
        INIT.length + i * SLOT_BYTES, SLOT_BYTES);
    return all;
  }

  private static byte[] bytes(int count, int seed) {
    byte[] data = new byte[count];
    for (int i = 0; i < count; i++) data[i] = (byte) (i * 31 + seed);
    return data;
  }

  private static LiveClipHttpServer server(BoundedMediaBuffer source,
      java.util.function.Consumer<LiveClipHttpServer.Event> listener) throws Exception {
    return new LiveClipHttpServer(source, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), listener);
  }

  private static void headers(Client c, int status, long length) {
    equal(status, c.status, "status");
    equal(Long.toString(length), c.header("content-length"), "exact Content-Length");
    equal("video/mp4", c.header("content-type"), "MIME");
    equal("bytes", c.header("accept-ranges"), "ranges");
    equal("close", c.header("connection"), "connection");
    equal("no-store", c.header("cache-control"), "no cache");
    check(c.header("transfer-encoding") == null, "never chunk encoded");
  }

  private static final class Fixture implements AutoCloseable {
    final BoundedMediaBuffer source;
    final BlockingQueue<LiveClipHttpServer.Event> events = new LinkedBlockingQueue<>();
    final LiveClipHttpServer server;
    Fixture(boolean complete) throws Exception {
      source = source(complete);
      server = server(source, events::add);
      server.start();
    }
    Client request(String method, String headers) throws Exception {
      return raw(method + " " + server.getPath() + " HTTP/1.1\r\n" + headers + "\r\n");
    }
    Client raw(String request) throws Exception { return new Client(server, request); }
    LiveClipHttpServer.TransferSnapshot snapshot(Predicate<LiveClipHttpServer.TransferSnapshot> predicate)
        throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        LiveClipHttpServer.TransferSnapshot value = server.getTransferSnapshot();
        if (predicate.test(value)) return value;
        Thread.sleep(5);
      }
      throw new AssertionError("snapshot condition not observed");
    }
    @Override public void close() { server.close(); source.close(); }
  }

  private static final class Client implements AutoCloseable {
    final Socket socket = new Socket();
    final InputStream input;
    final String protocol;
    final int status;
    final Map<String, String> fields = new LinkedHashMap<>();
    Client(LiveClipHttpServer server, String request) throws Exception {
      socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 1000);
      socket.setSoTimeout(7000);
      input = socket.getInputStream();
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      String[] statusLine = line(input).split(" ", 3);
      protocol = statusLine[0];
      status = Integer.parseInt(statusLine[1]);
      String header;
      while (!(header = line(input)).isEmpty()) {
        int colon = header.indexOf(':');
        fields.put(header.substring(0, colon).toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
      }
    }
    String header(String name) { return fields.get(name); }
    byte[] readFixed(int count) throws Exception {
      byte[] bytes = new byte[count];
      int offset = 0;
      while (offset < count) {
        int got = input.read(bytes, offset, count - offset);
        if (got < 0) throw new EOFException("body shorter than planned");
        offset += got;
      }
      return bytes;
    }
    void expectWaiting() throws Exception {
      socket.setSoTimeout(180);
      try { input.read(); throw new AssertionError("future body arrived before production"); }
      catch (SocketTimeoutException expected) { }
      finally { socket.setSoTimeout(7000); }
    }
    void expectEof() throws Exception { equal(-1, input.read(), "end of response"); }
    @Override public void close() throws IOException { socket.close(); }
  }

  private static String line(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    while (output.size() < 8192) {
      int value = input.read();
      if (value < 0) throw new EOFException("missing response headers");
      if (value == '\r') {
        if (input.read() != '\n') throw new IOException("bad line ending");
        return output.toString(StandardCharsets.US_ASCII.name());
      }
      output.write(value);
    }
    throw new IOException("response header too large");
  }

  private static void equal(long expected, long actual, String message) {
    if (expected != actual) throw new AssertionError(message + ": " + actual + " != " + expected);
  }
  private static void equal(String expected, String actual, String message) {
    if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": " + actual + " != " + expected);
  }
  private static void equalBytes(byte[] expected, byte[] actual, String message) {
    if (!Arrays.equals(expected, actual)) throw new AssertionError(message);
  }
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private interface Case { void run() throws Exception; }
  private static final class NamedCase {
    final String name; final Case body;
    NamedCase(String name, Case body) { this.name = name; this.body = body; }
  }
}
