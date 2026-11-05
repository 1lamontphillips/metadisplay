package com.lamontphillips.metadisplay.transporttest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone host tests. All payload bytes are synthetic; no device or media data is used. */
public final class ClipHttpServerTest {
  private static final byte[] CONTENT = syntheticContent();
  private static final int SOCKET_TIMEOUT_MS = 7000;
  private static final int MAX_RESPONSE_BYTES = 128 * 1024;

  private ClipHttpServerTest() {}

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    tests.add(new NamedCase("tokenized path and lifecycle", ClipHttpServerTest::lifecycle));
    tests.add(new NamedCase("GET full asset", ClipHttpServerTest::fullGet));
    tests.add(new NamedCase("HEAD full asset", ClipHttpServerTest::fullHead));
    tests.add(new NamedCase("one-byte range", () -> rangeGet("bytes=0-0", 0, 0)));
    tests.add(new NamedCase("bounded range", () -> rangeGet("bytes=7-24", 7, 24)));
    tests.add(new NamedCase("open-ended range", () -> rangeGet("bytes=4000-", 4000, CONTENT.length - 1)));
    tests.add(new NamedCase("suffix range", () -> rangeGet("bytes=-15", CONTENT.length - 15, CONTENT.length - 1)));
    tests.add(new NamedCase("oversized suffix clamps to asset", () -> rangeGet("bytes=-999999", 0, CONTENT.length - 1)));
    tests.add(new NamedCase("bounded range clamps its end", () -> rangeGet("bytes=4085-999999", 4085, CONTENT.length - 1)));
    tests.add(new NamedCase("last-byte range", () -> rangeGet("bytes=4095-4095", CONTENT.length - 1, CONTENT.length - 1)));
    tests.add(new NamedCase("HEAD range", ClipHttpServerTest::rangeHead));
    tests.add(new NamedCase("case-insensitive Range header", ClipHttpServerTest::mixedCaseRange));

    String[] invalidRanges = {
      "bytes=4096-", "bytes=999999-1000000", "bytes=10-9", "bytes=-0", "bytes=-",
      "bytes=", "bytes=12", "bytes=abc-def", "bytes=1-2,5-6", "items=0-4",
      "bytes=+1-2", "bytes=1-+2", "bytes=9223372036854775808-",
      "bytes=0-9223372036854775808", "bytes=-9223372036854775808",
      "bytes=999999999999999999999999999999999999999999-"
    };
    for (String range : invalidRanges) {
      tests.add(new NamedCase("reject range " + range, () -> invalidRange(range)));
    }

    tests.add(new NamedCase("wrong token and missing token return 404", ClipHttpServerTest::wrongPaths));
    tests.add(new NamedCase("traversal paths return 404", ClipHttpServerTest::traversalPaths));
    tests.add(new NamedCase("unsupported method returns 405", ClipHttpServerTest::unsupportedMethod));
    tests.add(new NamedCase("request and byte counters", ClipHttpServerTest::counters));
    tests.add(new NamedCase("overlong request line is bounded", ClipHttpServerTest::overlongRequestLine));
    tests.add(new NamedCase("overlong header line is bounded", ClipHttpServerTest::overlongHeaderLine));
    tests.add(new NamedCase("aggregate header bytes are bounded", ClipHttpServerTest::oversizedHeaders));
    tests.add(new NamedCase("header count is bounded", ClipHttpServerTest::tooManyHeaders));
    tests.add(new NamedCase("idle partial request times out", ClipHttpServerTest::idleTimeout));
    tests.add(new NamedCase("dribbled partial request times out", ClipHttpServerTest::absoluteTimeout));
    tests.add(new NamedCase("stop closes active and queued sockets", ClipHttpServerTest::stopClosesSockets));

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
    System.out.println("ClipHttpServerTest: " + (tests.size() - failures) + "/"
        + tests.size() + " passed; " + failures + " failed");
    if (failures != 0) {
      System.exit(1);
    }
  }

  private static void lifecycle() throws Exception {
    Fixture fixture = new Fixture();
    try {
      check(fixture.server.isRunning(), "server must be running after start");
      check(fixture.server.getPort() > 0 && fixture.server.getPort() <= 65535, "bound port");
      String path = fixture.server.getPath();
      check(path.matches("/[^/]+/sample\\.mp4"), "tokenized asset path: " + path);
      check(!path.contains(".."), "asset path must not contain traversal");
      equal(path, fixture.server.getPath(), "path is stable during the server lifetime");
      equal(0L, fixture.server.getRequestCount(), "initial request count");
      equal(0L, fixture.server.getBytesServed(), "initial byte count");
      fixture.server.close();
      fixture.server.close();
      check(!fixture.server.isRunning(), "close is idempotent and clears running state");
      try (Socket socket = new Socket()) {
        try {
          socket.connect(new InetSocketAddress(fixture.address, fixture.server.getPort()), 1000);
          throw new AssertionError("a stopped server accepted a new connection");
        } catch (IOException expected) {
          // A closed listening socket must refuse a new connection.
        }
      }
    } finally {
      fixture.close();
    }
  }

  private static void fullGet() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("GET", fixture.server.getPath(), "");
      assetHeaders(response, 200, CONTENT.length);
      check(!response.headers.containsKey("content-range"), "full GET has no Content-Range");
      equalBytes(CONTENT, response.body, "full GET body");
      event(fixture.nextEvent(), "GET", 200, CONTENT.length, 0, CONTENT.length - 1, true);
      equal(1L, fixture.server.getRequestCount(), "GET request count");
      equal((long) CONTENT.length, fixture.server.getBytesServed(), "GET byte count");
    }
  }

  private static void fullHead() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("HEAD", fixture.server.getPath(), "");
      assetHeaders(response, 200, CONTENT.length);
      equal(0L, response.body.length, "HEAD has no body");
      event(fixture.nextEvent(), "HEAD", 200, 0, 0, CONTENT.length - 1, true);
      equal(1L, fixture.server.getRequestCount(), "HEAD request count");
      equal(0L, fixture.server.getBytesServed(), "HEAD does not count advertised bytes as sent");
    }
  }

  private static void rangeGet(String range, int first, int last) throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("GET", fixture.server.getPath(), "Range: " + range + "\r\n");
      rangeHeaders(response, first, last);
      equalBytes(Arrays.copyOfRange(CONTENT, first, last + 1), response.body, "range body");
      int length = last - first + 1;
      event(fixture.nextEvent(), "GET", 206, length, first, last, true);
      equal((long) length, fixture.server.getBytesServed(), "range byte count");
    }
  }

  private static void rangeHead() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("HEAD", fixture.server.getPath(), "Range: bytes=12-31\r\n");
      rangeHeaders(response, 12, 31);
      equal(0L, response.body.length, "ranged HEAD has no body");
      event(fixture.nextEvent(), "HEAD", 206, 0, 12, 31, true);
      equal(0L, fixture.server.getBytesServed(), "ranged HEAD byte count");
    }
  }

  private static void mixedCaseRange() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("GET", fixture.server.getPath(), "rAnGe: bytes=20-29\r\n");
      rangeHeaders(response, 20, 29);
      equalBytes(Arrays.copyOfRange(CONTENT, 20, 30), response.body, "mixed-case header range body");
      event(fixture.nextEvent(), "GET", 206, 10, 20, 29, true);
    }
  }

  private static void invalidRange(String range) throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("GET", fixture.server.getPath(), "Range: " + range + "\r\n");
      equal(416L, response.status, "invalid range status");
      equal("bytes */" + CONTENT.length, response.header("content-range"), "416 Content-Range");
      emptyError(response);
      ClipHttpServer.Event event = fixture.nextEvent();
      eventBasics(event, "GET", 416, 0, true);
      equal(1L, fixture.server.getRequestCount(), "416 is an asset request");
      equal(0L, fixture.server.getBytesServed(), "416 serves no asset bytes");
    }
  }

  private static void wrongPaths() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String[] paths = {"/not-the-token/sample.mp4", "/sample.mp4", fixture.server.getPath() + "/extra"};
      for (String path : paths) {
        Response response = fixture.request("GET", path, "");
        equal(404L, response.status, "wrong path status for " + path);
        emptyError(response);
        eventBasics(fixture.nextEvent(), "GET", 404, 0, false);
      }
      equal(0L, fixture.server.getRequestCount(), "wrong paths do not count as asset requests");
      equal(0L, fixture.server.getBytesServed(), "wrong paths do not serve asset bytes");
    }
  }

  private static void traversalPaths() throws Exception {
    try (Fixture fixture = new Fixture()) {
      String directory = fixture.server.getPath().substring(0, fixture.server.getPath().lastIndexOf('/'));
      String[] paths = {
        directory + "/../sample.mp4", directory + "/%2e%2e/sample.mp4",
        "/../" + fixture.server.getPath().substring(1), directory + "/./sample.mp4"
      };
      for (String path : paths) {
        Response response = fixture.request("GET", path, "");
        equal(404L, response.status, "traversal status for " + path);
        emptyError(response);
        eventBasics(fixture.nextEvent(), "GET", 404, 0, false);
      }
      equal(0L, fixture.server.getRequestCount(), "traversal paths are not asset requests");
      equal(0L, fixture.server.getBytesServed(), "traversal paths serve no bytes");
    }
  }

  private static void unsupportedMethod() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Response response = fixture.request("POST", fixture.server.getPath(), "Content-Length: 0\r\n");
      equal(405L, response.status, "unsupported method status");
      emptyError(response);
      ClipHttpServer.Event event = fixture.nextEvent();
      equal("OTHER", event.method, "unsupported-method event method is sanitized");
      equal(405L, event.status, "unsupported-method event status");
      equal(0L, event.bytesSent, "unsupported method sends no body");
      nonemptyOutcome(event);
      equal(0L, fixture.server.getRequestCount(), "unsupported methods are not asset requests");
      equal(0L, fixture.server.getBytesServed(), "unsupported methods serve no bytes");
    }
  }

  private static void counters() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.request("GET", fixture.server.getPath(), "");
      fixture.nextEvent();
      fixture.request("HEAD", fixture.server.getPath(), "");
      fixture.nextEvent();
      fixture.request("GET", fixture.server.getPath(), "Range: bytes=8-23\r\n");
      fixture.nextEvent();
      fixture.request("GET", fixture.server.getPath(), "Range: bytes=4096-\r\n");
      fixture.nextEvent();
      fixture.request("GET", "/wrong/sample.mp4", "");
      fixture.nextEvent();
      fixture.request("POST", fixture.server.getPath(), "Content-Length: 0\r\n");
      fixture.nextEvent();
      equal(4L, fixture.server.getRequestCount(), "GET, HEAD, range and 416 count; other paths/methods do not");
      equal((long) CONTENT.length + 16, fixture.server.getBytesServed(), "only written GET bodies count");
    }
  }

  private static void overlongRequestLine() throws Exception {
    try (Fixture fixture = new Fixture()) {
      boundedRejection(fixture, "GET /" + repeat('x', 11000) + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
      assertStillServes(fixture);
    }
  }

  private static void overlongHeaderLine() throws Exception {
    try (Fixture fixture = new Fixture()) {
      boundedRejection(fixture, requestText("GET", fixture.server.getPath(), "X-Large: " + repeat('x', 11000) + "\r\n"));
      assertStillServes(fixture);
    }
  }

  private static void oversizedHeaders() throws Exception {
    try (Fixture fixture = new Fixture()) {
      StringBuilder headers = new StringBuilder();
      for (int index = 0; index < 80; index++) {
        headers.append("X-Bulk-").append(index).append(": ").append(repeat('x', 900)).append("\r\n");
      }
      boundedRejection(fixture, requestText("GET", fixture.server.getPath(), headers.toString()));
      assertStillServes(fixture);
    }
  }

  private static void tooManyHeaders() throws Exception {
    try (Fixture fixture = new Fixture()) {
      StringBuilder headers = new StringBuilder();
      for (int index = 0; index < 100; index++) {
        headers.append("X-Small-").append(index).append(": x\r\n");
      }
      boundedRejection(fixture, requestText("GET", fixture.server.getPath(), headers.toString()));
      assertStillServes(fixture);
    }
  }

  private static void idleTimeout() throws Exception {
    try (Fixture fixture = new Fixture(); Socket socket = fixture.connect()) {
      long started = System.nanoTime();
      socket.getOutputStream().write("GET ".getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertClosedOrRejected(socket, "idle partial request");
      check(elapsedMillis(started) < 6000, "idle request exceeded bounded timeout");
      equal(0L, fixture.server.getBytesServed(), "partial request serves no asset bytes");
      assertStillServes(fixture);
    }
  }

  private static void absoluteTimeout() throws Exception {
    try (Fixture fixture = new Fixture(); Socket socket = fixture.connect()) {
      AtomicBoolean stopWriting = new AtomicBoolean();
      OutputStream output = socket.getOutputStream();
      output.write("GET ".getBytes(StandardCharsets.US_ASCII));
      output.flush();
      Thread writer = new Thread(() -> {
        try {
          while (!stopWriting.get()) {
            output.write('x');
            output.flush();
            Thread.sleep(100);
          }
        } catch (IOException expectedAfterClose) {
          // The bounded server closes an unfinished request even while bytes keep arriving.
        } catch (InterruptedException expectedOnCleanup) {
          Thread.currentThread().interrupt();
        }
      }, "clip-http-test-dribble");
      writer.setDaemon(true);
      long started = System.nanoTime();
      writer.start();
      try {
        assertClosedOrRejected(socket, "dribbled partial request");
        check(elapsedMillis(started) < 6000, "active trickle bypassed the absolute timeout");
      } finally {
        stopWriting.set(true);
        writer.interrupt();
        writer.join(2000);
      }
      check(!writer.isAlive(), "test writer stopped");
      assertStillServes(fixture);
    }
  }

  private static void stopClosesSockets() throws Exception {
    try (Fixture fixture = new Fixture()) {
      List<Socket> clients = new ArrayList<>();
      try {
        // Four workers and eight queued requests must all be released by close().
        for (int index = 0; index < 12; index++) {
          Socket socket = fixture.connect();
          clients.add(socket);
          socket.getOutputStream().write(("GET " + fixture.server.getPath()
              + " HTTP/1.1\r\nHost: localhost\r\n").getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
        }
        long started = System.nanoTime();
        fixture.server.close();
        fixture.server.close();
        check(!fixture.server.isRunning(), "stopped server reports not running");
        for (Socket socket : clients) {
          assertClosedOrRejected(socket, "socket after server stop");
        }
        check(elapsedMillis(started) < 6000, "stop left active or queued sockets open");
        equal(0L, fixture.server.getBytesServed(), "stop of partial requests serves no asset bytes");
      } finally {
        for (Socket socket : clients) {
          socket.close();
        }
      }
    }
  }

  private static void assertStillServes(Fixture fixture) throws Exception {
    Response response = fixture.request("GET", fixture.server.getPath(), "");
    assetHeaders(response, 200, CONTENT.length);
    equalBytes(CONTENT, response.body, "server remains usable after rejecting a request");
  }

  private static void boundedRejection(Fixture fixture, String request) throws Exception {
    long started = System.nanoTime();
    try (Socket socket = fixture.connect()) {
      try {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      } catch (SocketException closedWhileWriting) {
        check(elapsedMillis(started) < 6000, "oversized request was not rejected promptly");
        return;
      }
      assertClosedOrRejected(socket, "oversized request");
    }
    check(elapsedMillis(started) < 6000, "oversized request exceeded bounded response time");
    equal(0L, fixture.server.getBytesServed(), "rejected request serves no asset bytes");
  }

  private static void assertClosedOrRejected(Socket socket, String description) throws Exception {
    byte[] bytes;
    try {
      bytes = readAll(socket.getInputStream());
    } catch (SocketTimeoutException timeout) {
      throw new AssertionError(description + " stayed open instead of being closed or rejected", timeout);
    } catch (SocketException closed) {
      return;
    }
    if (bytes.length == 0) {
      return;
    }
    Response response = Response.parse(bytes);
    check(response.status == 400 || response.status == 408 || response.status == 413
            || response.status == 414 || response.status == 431 || response.status == 503,
        description + " returned unexpected status " + response.status);
    emptyError(response);
  }

  private static void assetHeaders(Response response, int status, int length) {
    equal((long) status, response.status, "HTTP status");
    check(response.header("content-type").toLowerCase(Locale.ROOT).startsWith("video/mp4"), "MP4 Content-Type");
    equal(Integer.toString(length), response.header("content-length"), "Content-Length");
    equal("bytes", response.header("accept-ranges"), "Accept-Ranges");
  }

  private static void rangeHeaders(Response response, int first, int last) {
    assetHeaders(response, 206, last - first + 1);
    equal("bytes " + first + "-" + last + "/" + CONTENT.length,
        response.header("content-range"), "Content-Range");
  }

  private static void emptyError(Response response) {
    equal("0", response.header("content-length"), "empty error Content-Length");
    equal(0L, response.body.length, "error body is empty");
  }

  private static void event(ClipHttpServer.Event event, String method, int status,
      long bytes, long first, long last, boolean assetRequest) {
    eventBasics(event, method, status, bytes, assetRequest);
    equal(first, event.start, "event inclusive start");
    equal(last, event.end, "event inclusive end");
  }

  private static void eventBasics(ClipHttpServer.Event event, String method, int status,
      long bytes, boolean assetRequest) {
    equal(method, event.method, "event method");
    equal((long) status, event.status, "event status");
    equal(bytes, event.bytesSent, "event written body bytes");
    check(event.assetRequest == assetRequest, "event assetRequest");
    nonemptyOutcome(event);
  }

  private static void nonemptyOutcome(ClipHttpServer.Event event) {
    check(event.outcome != null && !event.outcome.trim().isEmpty(), "event outcome is present");
  }

  private static String requestText(String method, String path, String headers) {
    return method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
        + headers + "\r\n";
  }

  private static byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[2048];
    int count;
    while ((count = input.read(buffer)) != -1) {
      check(bytes.size() + count <= MAX_RESPONSE_BYTES, "response exceeds test bound");
      bytes.write(buffer, 0, count);
    }
    return bytes.toByteArray();
  }

  private static byte[] syntheticContent() {
    byte[] bytes = new byte[4096];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = (byte) ((index * 31 + index / 7) & 0xff);
    }
    return bytes;
  }

  private static String repeat(char value, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, value);
    return new String(chars);
  }

  private static long elapsedMillis(long started) {
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

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  @FunctionalInterface
  private interface TestCase {
    void run() throws Exception;
  }

  private static final class NamedCase {
    final String name;
    final TestCase body;

    NamedCase(String name, TestCase body) {
      this.name = name;
      this.body = body;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final InetAddress address;
    final ClipHttpServer server;
    private final BlockingQueue<ClipHttpServer.Event> events = new LinkedBlockingQueue<>();

    Fixture() throws IOException {
      address = InetAddress.getLoopbackAddress();
      server = new ClipHttpServer(CONTENT.clone(), address, events::add);
      try {
        server.start();
      } catch (IOException | RuntimeException failure) {
        server.close();
        throw failure;
      }
    }

    Socket connect() throws IOException {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, server.getPort()), 2500);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        return socket;
      } catch (IOException failure) {
        socket.close();
        throw failure;
      }
    }

    Response request(String method, String path, String headers) throws Exception {
      try (Socket socket = connect()) {
        socket.getOutputStream().write(requestText(method, path, headers).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return Response.parse(readAll(socket.getInputStream()));
      }
    }

    ClipHttpServer.Event nextEvent() throws InterruptedException {
      ClipHttpServer.Event event = events.poll(5, TimeUnit.SECONDS);
      check(event != null, "request event was not delivered");
      return event;
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static final class Response {
    final int status;
    final Map<String, String> headers;
    final byte[] body;

    Response(int status, Map<String, String> headers, byte[] body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }

    String header(String name) {
      String value = headers.get(name.toLowerCase(Locale.ROOT));
      check(value != null, "missing response header " + name);
      return value;
    }

    static Response parse(byte[] bytes) {
      int separator = -1;
      for (int index = 0; index + 3 < bytes.length; index++) {
        if (bytes[index] == '\r' && bytes[index + 1] == '\n'
            && bytes[index + 2] == '\r' && bytes[index + 3] == '\n') {
          separator = index;
          break;
        }
      }
      check(separator >= 0, "HTTP response lacks header terminator");
      String text = new String(bytes, 0, separator, StandardCharsets.ISO_8859_1);
      String[] lines = text.split("\r\n");
      String[] statusLine = lines[0].split(" +", 3);
      check(statusLine.length >= 2 && statusLine[0].startsWith("HTTP/1."), "HTTP status line");
      int status = Integer.parseInt(statusLine[1]);
      Map<String, String> headers = new LinkedHashMap<>();
      for (int index = 1; index < lines.length; index++) {
        int colon = lines[index].indexOf(':');
        check(colon > 0, "well-formed response header");
        String name = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = lines[index].substring(colon + 1).trim();
        check(!headers.containsKey(name), "duplicate response header " + name);
        headers.put(name, value);
      }
      return new Response(status, headers, Arrays.copyOfRange(bytes, separator + 4, bytes.length));
    }
  }
}
