package com.lamontphillips.metadisplay.transporttest;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One-shot HTTP transport experiment for immutable public fMP4 bytes. No filesystem or camera access.
 * Byte counters exclude HTTP chunk framing. A complete response includes the optional EOF hold.
 */
public final class StreamProbeHttpServer implements AutoCloseable {
  private static final int READ_TIMEOUT_MS = 1500;
  private static final int MAX_REQUEST_LINE = 4096;
  private static final int MAX_HEADER_LINE = 4096;
  private static final int MAX_HEADER_BYTES = 16384;
  private static final int MAX_HEADERS = 64;
  private static final int WORKER_COUNT = 4;
  private static final int QUEUE_SIZE = 8;

  public enum Delivery { FILE, PACED_FILE, PACED_CHUNKED }
  public enum Phase { STARTED, PROGRESS, COMPLETE }

  /** Safe protocol summary. start/end are inclusive asset offsets, or -1 when absent. */
  public static final class Event {
    public final String method;
    public final int status;
    public final long bytesSent;
    public final long start;
    public final long end;
    public final String outcome;
    public final boolean assetRequest;
    public final long transferId;
    public final Phase phase;

    private Event(Request request, Phase phase) {
      method = request.method;
      status = request.status;
      bytesSent = request.bytesSent;
      start = request.start;
      end = request.end;
      outcome = request.outcome;
      assetRequest = request.assetRequest;
      transferId = request.transferId;
      this.phase = phase;
    }
  }

  /** Most recently started successful asset GET, plus live counts across all such GETs. */
  public static final class TransferSnapshot {
    public final long id;
    public final long bytesSent;
    public final long plannedBytes;
    public final boolean responseComplete;
    public final boolean payloadComplete;
    public final int activeGetCount;
    public final long totalGetCount;
    public final long start;
    public final long end;
    public final int status;
    public final boolean rangeRequested;
    public final boolean writeInProgress;

    private TransferSnapshot(long id, long bytesSent, long plannedBytes,
        boolean responseComplete, boolean payloadComplete, int activeGetCount,
        long totalGetCount, long start, long end, int status, boolean rangeRequested,
        boolean writeInProgress) {
      this.id = id;
      this.bytesSent = bytesSent;
      this.plannedBytes = plannedBytes;
      this.responseComplete = responseComplete;
      this.payloadComplete = payloadComplete;
      this.activeGetCount = activeGetCount;
      this.totalGetCount = totalGetCount;
      this.start = start;
      this.end = end;
      this.status = status;
      this.rangeRequested = rangeRequested;
      this.writeInProgress = writeInProgress;
    }
  }

  private final byte[] content;
  private final InetAddress bindAddress;
  private final Delivery delivery;
  private final long pacingDurationMs;
  private final long eofHoldMs;
  private final Object transfers = new Object();
  private long nextTransferId;
  private long totalGetCount;
  private int activeGetCount;
  private TransferSnapshot latestTransfer = new TransferSnapshot(
      0, 0, 0, false, false, 0, 0, -1, -1, 0, false, false);
  private final Consumer<Event> listener;
  private final String path;
  private final Object lifecycle = new Object();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong requestCount = new AtomicLong();
  private final AtomicLong bytesServed = new AtomicLong();
  private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
  private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
  private ServerSocket server;
  private ThreadPoolExecutor workers;
  private Thread acceptThread;
  private volatile int port = -1;
  private boolean closed;

  /** Listener callbacks can occur concurrently on server threads and must return promptly. */
  public StreamProbeHttpServer(byte[] content, InetAddress bindAddress, Delivery delivery,
      long pacingDurationMs, long eofHoldMs, Consumer<Event> listener) {
    this.content = Objects.requireNonNull(content, "content").clone();
    this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.delivery = Objects.requireNonNull(delivery, "delivery");
    if (pacingDurationMs < 0 || pacingDurationMs > 120000 || eofHoldMs < 0 || eofHoldMs > 30000) {
      throw new IllegalArgumentException("Invalid finite pacing or EOF hold duration");
    }
    this.pacingDurationMs = pacingDurationMs;
    this.eofHoldMs = eofHoldMs;
    if (!bindAddress.isLoopbackAddress() && !bindAddress.isAnyLocalAddress()) {
      throw new IllegalArgumentException("Use loopback or wildcard binding");
    }
    byte[] token = new byte[16];
    new SecureRandom().nextBytes(token);
    StringBuilder encoded = new StringBuilder(32);
    for (byte value : token) {
      encoded.append(Character.forDigit((value >>> 4) & 15, 16));
      encoded.append(Character.forDigit(value & 15, 16));
    }
    path = "/" + encoded + "/sample.mp4";
  }

  /** Starts on an OS-selected port. Repeated start while running is harmless; close is terminal. */
  public void start() throws IOException {
    synchronized (lifecycle) {
      if (closed) throw new IOException("Server is closed");
      if (running.get()) return;
      ServerSocket socket = new ServerSocket();
      try {
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(bindAddress, 0), WORKER_COUNT + QUEUE_SIZE);
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_SIZE), task -> {
              Thread thread = new Thread(() -> {
                workerThreads.add(Thread.currentThread());
                try {
                  task.run();
                } finally {
                  workerThreads.remove(Thread.currentThread());
                }
              }, "transport-stream-worker-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        server = socket;
        workers = pool;
        port = socket.getLocalPort();
        running.set(true);
        acceptThread = new Thread(() -> acceptConnections(socket, pool), "transport-stream-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
      } catch (IOException | RuntimeException failure) {
        closeQuietly(socket);
        running.set(false);
        if (workers != null) workers.shutdownNow();
        throw failure;
      }
    }
  }

  public int getPort() { return port; }
  public String getPath() { return path; }
  public boolean isRunning() { return running.get(); }
  /** Parsed asset GET/HEAD requests, including unsatisfiable ranges; excludes malformed requests. */
  public long getRequestCount() { return requestCount.get(); }
  /** Body bytes in completed socket writes; excludes headers, HEAD and empty error responses. */
  public long getBytesServed() { return bytesServed.get(); }
  public TransferSnapshot getTransferSnapshot() {
    synchronized (transfers) { return latestTransfer; }
  }

  private void acceptConnections(ServerSocket socket, ThreadPoolExecutor pool) {
    try {
      while (running.get()) {
        Socket connection = socket.accept();
        connections.add(connection);
        if (!running.get()) {
          connections.remove(connection);
          closeQuietly(connection);
          continue;
        }
        try {
          connection.setSoTimeout(READ_TIMEOUT_MS);
          connection.setTcpNoDelay(true);
          pool.execute(() -> serve(connection));
        } catch (RejectedExecutionException full) {
          Request rejected = new Request();
          rejected.status = 0; // No HTTP response is written when the bounded queue is full.
          rejected.outcome = running.get() ? "busy" : "stopped";
          // Never block the accepting thread on a client that is not reading.
          connections.remove(connection);
          closeQuietly(connection);
          emit(rejected);
        } catch (IOException failure) {
          connections.remove(connection);
          closeQuietly(connection);
          Request failed = new Request();
          failed.outcome = running.get() ? "connection_failed" : "stopped";
          emit(failed);
        }
      }
    } catch (IOException failure) {
      if (running.get()) {
        Request failed = new Request();
        failed.outcome = "accept_failed";
        emit(failed);
      }
    } finally {
      close();
    }
  }

  private void serve(Socket connection) {
    Request request = new Request();
    try {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MS);
      InputStream input = new BufferedInputStream(connection.getInputStream(), 1024);
      String requestLine = readLine(input, connection, deadline, MAX_REQUEST_LINE, 414,
          "request_line_too_long");
      parseRequestLine(requestLine, request);
      String range = readHeaders(input, connection, deadline);
      if (!path.equals(request.target)) throw new HttpProblem(404, "not_found");
      if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
        throw new HttpProblem(405, "method_not_allowed");
      }
      request.assetRequest = true;
      request.rangeRequested = range != null;
      requestCount.incrementAndGet();
      boolean chunked = delivery == Delivery.PACED_CHUNKED;
      request.rangeIgnored = chunked && range != null;
      long[] selected = selectRange(chunked ? null : range);
      request.start = selected[0];
      request.end = selected[1];
      request.status = chunked || range == null ? 200 : 206;
      request.plannedBytes = request.end < request.start ? 0 : request.end - request.start + 1;
      if ("GET".equals(request.method)) {
        beginTransfer(request);
        request.outcome = request.rangeIgnored ? "range_ignored" : "started";
        emit(request, Phase.STARTED);
      }
      OutputStream output = connection.getOutputStream();
      writeHeaders(output, request.status, request.plannedBytes, request.start, request.end);
      if ("GET".equals(request.method)) {
        sendPayload(output, request, chunked);
        request.payloadComplete = true;
        updateTransfer(request, false);
        progress(request, true);
        if (eofHoldMs > 0) sleepUntil(
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(eofHoldMs), request);
        ensureServing();
        if (chunked) output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        request.responseComplete = true;
        updateTransfer(request, false);
      }
      request.outcome = request.rangeIgnored ? "served_range_ignored" : "served";
    } catch (HttpProblem invalid) {
      request.status = invalid.status;
      request.outcome = invalid.outcome;
      sendError(connection, request);
    } catch (SocketTimeoutException timeout) {
      request.status = 408;
      request.outcome = "read_timeout";
      sendError(connection, request);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      request.outcome = "stopped";
    } catch (IOException disconnected) {
      request.outcome = running.get()
          ? (request.status == 0 ? "client_disconnected" : "write_failed") : "stopped";
    } catch (RuntimeException failure) {
      request.outcome = "server_error";
    } finally {
      if (request.transferId != 0) updateTransfer(request, true);
      connections.remove(connection);
      closeQuietly(connection);
      emit(request, Phase.COMPLETE);
    }
  }

  private void beginTransfer(Request request) {
    synchronized (transfers) {
      request.transferId = ++nextTransferId;
      request.lastProgress = System.nanoTime();
      totalGetCount++;
      activeGetCount++;
      latestTransfer = snapshot(request);
    }
  }

  private TransferSnapshot snapshot(Request request) {
    return new TransferSnapshot(request.transferId, request.bytesSent, request.plannedBytes,
        request.responseComplete, request.payloadComplete, activeGetCount, totalGetCount,
        request.start, request.end, request.status, request.rangeRequested, request.writeInProgress);
  }

  private void updateTransfer(Request request, boolean finished) {
    synchronized (transfers) {
      if (finished) {
        activeGetCount--;
        request.writeInProgress = false;
      }
      if (latestTransfer.id == request.transferId) {
        latestTransfer = snapshot(request);
      } else {
        // An older GET must not replace the most recently started GET's data or flags.
        TransferSnapshot latest = latestTransfer;
        latestTransfer = new TransferSnapshot(latest.id, latest.bytesSent, latest.plannedBytes,
            latest.responseComplete, latest.payloadComplete, activeGetCount, totalGetCount,
            latest.start, latest.end, latest.status, latest.rangeRequested, latest.writeInProgress);
      }
    }
  }

  private void sendPayload(OutputStream output, Request request, boolean chunked)
      throws IOException, InterruptedException {
    int offset = (int) request.start;
    int remaining = (int) request.plannedBytes;
    long durationMs = delivery == Delivery.FILE || content.length == 0 ? 0 :
        pacingDurationMs * request.plannedBytes / content.length;
    long started = System.nanoTime();
    // A small initial write exposes headers/init bytes without deliberately sending the full clip.
    int initial = durationMs > 0 && remaining > 1 ?
        Math.min(4096, Math.max(1, remaining / 32)) : Math.min(16384, remaining);
    if (initial > 0) {
      writeBodyChunk(output, request, offset, initial, chunked);
      offset += initial;
      remaining -= initial;
    }
    int pacedBytes = remaining;
    long durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
    while (remaining > 0) {
      int amount = Math.min(delivery == Delivery.FILE ? 16384 : 4096, remaining);
      if (durationNanos > 0) {
        long sent = pacedBytes - remaining + amount;
        // Split the ratio so even the largest Java byte array cannot overflow the product.
        long scheduled = started + durationNanos / pacedBytes * sent
            + durationNanos % pacedBytes * sent / pacedBytes;
        sleepUntil(scheduled, request);
      }
      writeBodyChunk(output, request, offset, amount, chunked);
      offset += amount;
      remaining -= amount;
      progress(request, false);
    }
  }

  private void writeBodyChunk(OutputStream output, Request request, int offset, int amount,
      boolean chunked) throws IOException, InterruptedException {
    ensureServing();
    if (chunked) {
      output.write((Integer.toHexString(amount) + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }
    request.writeInProgress = true;
    updateTransfer(request, false);
    output.write(content, offset, amount);
    request.bytesSent += amount;
    bytesServed.addAndGet(amount);
    request.payloadComplete = request.bytesSent == request.plannedBytes;
    request.writeInProgress = false;
    updateTransfer(request, false);
    if (chunked) output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
  }

  private void progress(Request request, boolean force) {
    long now = System.nanoTime();
    if (force || now - request.lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
      request.lastProgress = now;
      request.outcome = request.rangeIgnored ? "range_ignored" : "progress";
      emit(request, Phase.PROGRESS);
    }
  }

  private void sleepUntil(long deadline, Request request) throws InterruptedException {
    while (true) {
      ensureServing();
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return;
      TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)));
      progress(request, false);
    }
  }

  private void ensureServing() throws InterruptedException {
    if (!running.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
  }

  private static void parseRequestLine(String line, Request request) throws HttpProblem {
    if (line == null) throw new HttpProblem(400, "malformed_request");
    int first = line.indexOf(' ');
    int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
    if (first <= 0 || second <= first + 1 || line.indexOf(' ', second + 1) >= 0) {
      throw new HttpProblem(400, "malformed_request");
    }
    String method = line.substring(0, first);
    if (!isToken(method)) throw new HttpProblem(400, "malformed_request");
    request.method = "GET".equals(method) || "HEAD".equals(method) ? method : "OTHER";
    String version = line.substring(second + 1);
    if (!"HTTP/1.0".equals(version) && !"HTTP/1.1".equals(version)) {
      throw new HttpProblem(400, "malformed_request");
    }
    request.target = line.substring(first + 1, second);
    // Exact comparison below rejects traversal, query strings, encoded paths and absolute URLs.
  }

  private static String readHeaders(InputStream input, Socket connection, long deadline)
      throws IOException, HttpProblem {
    int total = 0;
    int count = 0;
    String range = null;
    boolean sawLength = false;
    while (true) {
      String line = readLine(input, connection, deadline, MAX_HEADER_LINE, 431,
          "headers_too_large");
      if (line == null) throw new HttpProblem(400, "malformed_request");
      total += line.length() + 2;
      if (total > MAX_HEADER_BYTES || (!line.isEmpty() && ++count > MAX_HEADERS)) {
        throw new HttpProblem(431, "headers_too_large");
      }
      if (line.isEmpty()) return range;
      int colon = line.indexOf(':');
      if (colon <= 0 || !isToken(line.substring(0, colon))) {
        throw new HttpProblem(400, "malformed_request");
      }
      String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if ("range".equals(name)) {
        // Repeated Range fields combine as a range set. File modes reject multiple ranges;
        // chunked mode ignores the complete Range field, including malformed range sets.
        range = range == null ? value : range + "," + value;
      } else if ("transfer-encoding".equals(name)) {
        throw new HttpProblem(400, "malformed_request");
      } else if ("content-length".equals(name)) {
        if (sawLength || !"0".equals(value)) throw new HttpProblem(400, "malformed_request");
        sawLength = true;
      }
    }
  }

  private long[] selectRange(String range) throws HttpProblem {
    if (range == null) return new long[] {0, content.length - 1L};
    if (!range.regionMatches(true, 0, "bytes=", 0, 6)
        || range.indexOf(',') >= 0 || content.length == 0) {
      throw new HttpProblem(416, "range_not_satisfiable");
    }
    String value = range.substring(6);
    int dash = value.indexOf('-');
    if (dash < 0 || value.indexOf('-', dash + 1) >= 0) {
      throw new HttpProblem(416, "range_not_satisfiable");
    }
    String left = value.substring(0, dash);
    String right = value.substring(dash + 1);
    if (left.isEmpty()) {
      long suffix = decimal(right);
      if (suffix == 0) throw new HttpProblem(416, "range_not_satisfiable");
      return new long[] {Math.max(0L, content.length - suffix), content.length - 1L};
    }
    long start = decimal(left);
    long end = right.isEmpty() ? content.length - 1L : decimal(right);
    if (start >= content.length || end < start) {
      throw new HttpProblem(416, "range_not_satisfiable");
    }
    return new long[] {start, Math.min(end, content.length - 1L)};
  }

  private static long decimal(String value) throws HttpProblem {
    if (value.isEmpty()) throw new HttpProblem(416, "range_not_satisfiable");
    long result = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9' || result > (Long.MAX_VALUE - (c - '0')) / 10) {
        throw new HttpProblem(416, "range_not_satisfiable");
      }
      result = result * 10 + c - '0';
    }
    return result;
  }

  private static String readLine(InputStream input, Socket connection, long deadline,
      int limit, int tooLongStatus, String tooLongOutcome) throws IOException, HttpProblem {
    ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(limit, 128));
    while (true) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new SocketTimeoutException();
      connection.setSoTimeout((int) Math.max(1L,
          Math.min(READ_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining))));
      int next = input.read();
      if (next < 0) {
        if (line.size() == 0) return null;
        throw new HttpProblem(400, "malformed_request");
      }
      if (next == '\r') {
        remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException();
        connection.setSoTimeout((int) Math.max(1L,
            Math.min(READ_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining))));
        if (input.read() != '\n') throw new HttpProblem(400, "malformed_request");
        return line.toString(StandardCharsets.US_ASCII.name());
      }
      if (next == '\n' || (next < 32 && next != '\t') || next > 126) {
        throw new HttpProblem(400, "malformed_request");
      }
      if (line.size() >= limit) throw new HttpProblem(tooLongStatus, tooLongOutcome);
      line.write(next);
    }
  }

  private static boolean isToken(String text) {
    if (text.isEmpty()) return false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
          && !(c >= '0' && c <= '9') && "!#$%&'*+-.^_`|~".indexOf(c) < 0) return false;
    }
    return true;
  }

  private void sendError(Socket socket, Request request) {
    try {
      writeHeaders(socket.getOutputStream(), request.status, 0, -1, -1);
    } catch (IOException ignored) {
      // Preserve the safe protocol diagnosis if the peer has already disconnected.
    }
  }

  private void writeHeaders(OutputStream output, int status, long length, long start, long end)
      throws IOException {
    String reason;
    switch (status) {
      case 200: reason = "OK"; break;
      case 206: reason = "Partial Content"; break;
      case 404: reason = "Not Found"; break;
      case 405: reason = "Method Not Allowed"; break;
      case 408: reason = "Request Timeout"; break;
      case 414: reason = "URI Too Long"; break;
      case 416: reason = "Range Not Satisfiable"; break;
      case 431: reason = "Request Header Fields Too Large"; break;
      default: reason = "Bad Request";
    }
    StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
        .append(reason).append("\r\nConnection: close\r\nCache-Control: no-store\r\n")
        .append("X-Content-Type-Options: nosniff\r\n");
    if (delivery == Delivery.PACED_CHUNKED && status == 200) {
      headers.append("Transfer-Encoding: chunked\r\nAccept-Ranges: none\r\n");
    } else {
      headers.append("Content-Length: ").append(length).append("\r\n");
      if (status == 200 || status == 206 || status == 416) {
        headers.append("Accept-Ranges: bytes\r\n");
      }
    }
    if (status == 200 || status == 206 || status == 416) {
      headers.append("Content-Type: video/mp4\r\n");
    }
    if (status == 206) {
      headers.append("Content-Range: bytes ").append(start).append('-').append(end)
          .append('/').append(content.length).append("\r\n");
    } else if (status == 416) {
      headers.append("Content-Range: bytes */").append(content.length).append("\r\n");
    } else if (status == 405) {
      headers.append("Allow: GET, HEAD\r\n");
    }
    headers.append("\r\n");
    output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
  }

  private void emit(Request request) { emit(request, Phase.COMPLETE); }

  private void emit(Request request, Phase phase) {
    try {
      listener.accept(new Event(request, phase));
    } catch (RuntimeException ignored) {
      // A UI listener must not keep a connection open or terminate server workers.
    }
  }

  /** Closes active/queued sockets and joins owned threads; safe from an event callback. */
  @Override public void close() {
    ServerSocket toClose;
    ThreadPoolExecutor toStop;
    Thread toJoin;
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
      running.set(false);
      toClose = server;
      toStop = workers;
      toJoin = acceptThread;
    }
    closeQuietly(toClose);
    for (Socket connection : connections) closeQuietly(connection);
    connections.clear();
    if (toStop != null) toStop.shutdownNow();
    boolean interrupted = false;
    if (toJoin != null && toJoin != Thread.currentThread()) {
      try {
        toJoin.join(2000);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (toStop != null && !workerThreads.contains(Thread.currentThread())) {
      try {
        toStop.awaitTermination(2000, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) return;
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Cleanup only; never include exception text in transport events.
    }
  }

  private static final class Request {
    String method = "UNKNOWN";
    String target;
    int status;
    long bytesSent;
    long plannedBytes;
    long transferId;
    long lastProgress = System.nanoTime();
    boolean payloadComplete;
    boolean responseComplete;
    boolean rangeRequested;
    boolean rangeIgnored;
    boolean writeInProgress;
    long start = -1;
    long end = -1;
    String outcome = "client_disconnected";
    boolean assetRequest;
  }

  private static final class HttpProblem extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;
    final String outcome;
    HttpProblem(int status, String outcome) {
      this.status = status;
      this.outcome = outcome;
    }
  }
}
