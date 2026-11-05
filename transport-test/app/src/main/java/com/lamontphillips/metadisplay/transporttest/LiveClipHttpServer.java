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

/** Bounded in-memory media HTTP source. Serves only a truthful planned response on loopback. */
public final class LiveClipHttpServer implements AutoCloseable {
  private static final int READ_TIMEOUT_MS = 1500;
  private static final int MAX_REQUEST_LINE = 4096;
  private static final int MAX_HEADER_LINE = 4096;
  private static final int MAX_HEADER_BYTES = 16384;
  private static final int MAX_HEADERS = 64;
  private static final int WORKER_COUNT = 4;
  private static final int QUEUE_SIZE = 8;
  private static final long DEFAULT_RESPONSE_LIMIT_MS = 75000;
  private static final long MAX_RESPONSE_LIMIT_MS = 180000;

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
    public final boolean rangeRequested;
    public final String requestProtocol;
    public final String responseProtocol;
    public final String framing;

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
      rangeRequested = request.rangeRequested;
      requestProtocol = request.requestProtocol;
      responseProtocol = request.responseProtocol;
      framing = request.framing;
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
    public final String requestProtocol;
    public final String responseProtocol;
    public final String framing;
    public final long sourceBytesProduced;

    private TransferSnapshot(long id, long bytesSent, long plannedBytes,
        boolean responseComplete, boolean payloadComplete, int activeGetCount,
        long totalGetCount, long start, long end, int status, boolean rangeRequested,
        boolean writeInProgress, String requestProtocol, String responseProtocol, String framing,
        long sourceBytesProduced) {
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
      this.requestProtocol = requestProtocol;
      this.responseProtocol = responseProtocol;
      this.framing = framing;
      this.sourceBytesProduced = sourceBytesProduced;
    }
  }

  private final BoundedMediaBuffer source;
  private final long responseLimitMs;
  private final AtomicLong maximumProducedBytes = new AtomicLong();
  private final InetAddress bindAddress;
  private final Object transfers = new Object();
  private long nextTransferId;
  private long totalGetCount;
  private int activeGetCount;
  private TransferSnapshot latestTransfer = new TransferSnapshot(
      0, 0, 0, false, false, 0, 0, -1, -1, 0, false, false, "UNKNOWN", "UNKNOWN", "UNKNOWN", 0);
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
  public LiveClipHttpServer(BoundedMediaBuffer source, InetAddress bindAddress,
      Consumer<Event> listener) {
    this(source, bindAddress, DEFAULT_RESPONSE_LIMIT_MS, listener);
  }

  /** One finite response may wait for production only within this bounded deadline. */
  public LiveClipHttpServer(BoundedMediaBuffer source, InetAddress bindAddress,
      long responseLimitMs, Consumer<Event> listener) {
    this.source = Objects.requireNonNull(source, "source");
    this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
    this.listener = Objects.requireNonNull(listener, "listener");
    if (responseLimitMs < 1 || responseLimitMs > MAX_RESPONSE_LIMIT_MS) {
      throw new IllegalArgumentException("Invalid finite response deadline");
    }
    this.responseLimitMs = responseLimitMs;
    if (!bindAddress.isLoopbackAddress()) {
      throw new IllegalArgumentException("Loopback binding required");
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
              }, "transport-live-worker-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        server = socket;
        workers = pool;
        port = socket.getLocalPort();
        running.set(true);
        acceptThread = new Thread(() -> acceptConnections(socket, pool), "transport-live-accept");
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
  public long getResponseLimitMs() { return responseLimitMs; }
  public String getPath() { return path; }
  public boolean isRunning() { return running.get(); }
  /** Parsed asset GET/HEAD requests, including unsatisfiable ranges; excludes malformed requests. */
  public long getRequestCount() { return requestCount.get(); }
  /** Body bytes in completed socket writes; excludes headers, HEAD and empty error responses. */
  public long getBytesServed() { return bytesServed.get(); }
  public TransferSnapshot getTransferSnapshot() {
    synchronized (transfers) {
      TransferSnapshot latest = latestTransfer;
      return new TransferSnapshot(latest.id, latest.bytesSent, latest.plannedBytes,
          latest.responseComplete, latest.payloadComplete, activeGetCount, totalGetCount,
          latest.start, latest.end, latest.status, latest.rangeRequested, latest.writeInProgress,
          latest.requestProtocol, latest.responseProtocol, latest.framing, producedBytes());
    }
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

  private long producedBytes() {
    return maximumProducedBytes.accumulateAndGet(source.getProducedBytes(), Math::max);
  }

  private long awaitSource() throws HttpProblem, InterruptedException {
    try {
      if (!source.awaitInitialized(5000)) throw new HttpProblem(503, "source_timeout");
      long length = source.getContentLength();
      if (length <= 0) throw new HttpProblem(503, "source_unavailable");
      return length;
    } catch (IOException unavailable) {
      throw new HttpProblem(503, "source_unavailable");
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
      request.responseProtocol = request.requestProtocol;
      String range = readHeaders(input, connection, deadline);
      if (!path.equals(request.target)) throw new HttpProblem(404, "not_found");
      if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
        throw new HttpProblem(405, "method_not_allowed");
      }
      request.assetRequest = true;
      request.rangeRequested = range != null;
      requestCount.incrementAndGet();
      request.fullLength = awaitSource();
      long[] selected = selectRange(range, request.fullLength);
      request.start = selected[0];
      request.end = selected[1];
      request.status = range == null ? 200 : 206;
      request.plannedBytes = request.end < request.start ? 0 : request.end - request.start + 1;
      if ("GET".equals(request.method)) {
        beginTransfer(request);
        request.outcome = "started";
        emit(request, Phase.STARTED);
      }
      OutputStream output = connection.getOutputStream();
      writeHeaders(output, request, request.plannedBytes);
      request.headersSent = true;
      if ("GET".equals(request.method)) {
        sendPayload(output, request);
        request.payloadComplete = true;
        updateTransfer(request, false);
        output.flush();
        request.responseComplete = true;
        updateTransfer(request, false);
      }
      request.outcome = "served";
    } catch (HttpProblem invalid) {
      request.status = invalid.status;
      request.outcome = invalid.outcome;
      sendError(connection, request);
    } catch (SocketTimeoutException timeout) {
      if (!request.headersSent) {
        request.status = 408;
        request.outcome = "read_timeout";
        sendError(connection, request);
      } else {
        request.outcome = "source_timeout";
      }
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
        request.start, request.end, request.status, request.rangeRequested, request.writeInProgress,
        request.requestProtocol, request.responseProtocol, request.framing, producedBytes());
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
            latest.start, latest.end, latest.status, latest.rangeRequested, latest.writeInProgress,
            latest.requestProtocol, latest.responseProtocol, latest.framing, producedBytes());
      }
    }
  }

  private void sendPayload(OutputStream output, Request request)
      throws IOException, InterruptedException {
    long offset = request.start;
    long remaining = request.plannedBytes;
    byte[] chunk = new byte[16384];
    long lastData = System.nanoTime();
    long lastProduced = producedBytes();
    long responseStarted = lastData;
    long responseBudgetNs = TimeUnit.MILLISECONDS.toNanos(responseLimitMs);
    while (remaining > 0) {
      ensureServing();
      long now = System.nanoTime();
      long responseRemainingNs = responseBudgetNs - (now - responseStarted);
      if (responseRemainingNs <= 0) throw new SocketTimeoutException("Response deadline");
      long waitMs = Math.max(1L, Math.min(500L,
          (responseRemainingNs + 999999L) / 1000000L));
      int amount;
      try {
        amount = source.readAt(offset, chunk, 0, (int) Math.min(chunk.length, remaining), waitMs);
      } catch (SocketTimeoutException waiting) {
        // A seek to a future byte can legitimately wait while earlier fragments are
        // still being produced. Treat source progress as activity, with the same
        // overall response deadline; only a stalled producer hits the idle limit.
        long currentProduced = producedBytes();
        if (currentProduced > lastProduced) {
          lastProduced = currentProduced;
          lastData = System.nanoTime();
        }
        if (System.nanoTime() - lastData >= TimeUnit.SECONDS.toNanos(10)) throw waiting;
        progress(request);
        continue;
      }
      if (amount <= 0) throw new IOException("Incomplete media source");
      ensureServing();
      if (System.nanoTime() - responseStarted >= responseBudgetNs) {
        throw new SocketTimeoutException("Response deadline");
      }
      request.writeInProgress = true;
      updateTransfer(request, false);
      output.write(chunk, 0, amount);
      request.bytesSent += amount;
      bytesServed.addAndGet(amount);
      request.payloadComplete = request.bytesSent == request.plannedBytes;
      request.writeInProgress = false;
      updateTransfer(request, false);
      offset += amount;
      remaining -= amount;
      lastData = System.nanoTime();
      lastProduced = producedBytes();
      progress(request);
    }
  }

  private void progress(Request request) {
    long now = System.nanoTime();
    if (now - request.lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
      request.lastProgress = now;
      request.outcome = "progress";
      emit(request, Phase.PROGRESS);
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
    request.requestProtocol = version;
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

  private long[] selectRange(String range, long fullLength) throws HttpProblem {
    if (range == null) return new long[] {0, fullLength - 1L};
    if (!range.regionMatches(true, 0, "bytes=", 0, 6)
        || range.indexOf(',') >= 0 || fullLength == 0) {
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
      return new long[] {Math.max(0L, fullLength - suffix), fullLength - 1L};
    }
    long start = decimal(left);
    long end = right.isEmpty() ? fullLength - 1L : decimal(right);
    if (start >= fullLength || end < start) {
      throw new HttpProblem(416, "range_not_satisfiable");
    }
    return new long[] {start, Math.min(end, fullLength - 1L)};
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
      writeHeaders(socket.getOutputStream(), request, 0);
    } catch (IOException ignored) {
      // Preserve the safe protocol diagnosis if the peer has already disconnected.
    }
  }

  private void writeHeaders(OutputStream output, Request request, long length) throws IOException {
    int status = request.status;
    // Error responses always have a known, empty body regardless of the configured mode.
    if (status != 200 && status != 206) request.framing = "CONTENT_LENGTH";
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
      case 503: reason = "Service Unavailable"; break;
      default: reason = "Bad Request";
    }
    StringBuilder headers = new StringBuilder(request.responseProtocol).append(' ')
        .append(status).append(' ')
        .append(reason).append("\r\nConnection: close\r\nCache-Control: no-store\r\n")
        .append("X-Content-Type-Options: nosniff\r\n");
    headers.append("Content-Length: ").append(length).append("\r\n");
    if (status == 200 || status == 206 || status == 416) {
      headers.append("Accept-Ranges: bytes\r\n");
    }
    if (status == 200 || status == 206 || status == 416) {
      headers.append("Content-Type: video/mp4\r\n");
    }
    if (status == 206) {
      headers.append("Content-Range: bytes ").append(request.start).append('-').append(request.end)
          .append('/').append(request.fullLength).append("\r\n");
    } else if (status == 416) {
      headers.append("Content-Range: bytes */").append(request.fullLength).append("\r\n");
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
    if (toJoin == Thread.currentThread() || workerThreads.contains(Thread.currentThread())) return;
    boolean interrupted = false;
    if (toJoin != null) {
      try {
        toJoin.join(2000);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (toStop != null) {
      try {
        toStop.awaitTermination(2000, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    if (interrupted || (toJoin != null && toJoin.isAlive())
        || (toStop != null && !toStop.isTerminated())) {
      throw new IllegalStateException("Live server stop unconfirmed");
    }
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
    boolean writeInProgress;
    boolean headersSent;
    long fullLength;
    String requestProtocol = "UNKNOWN";
    String responseProtocol = "HTTP/1.1";
    String framing = "CONTENT_LENGTH";
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
