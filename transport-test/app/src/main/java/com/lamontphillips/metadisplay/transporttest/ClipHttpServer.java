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

/** One-shot, bounded HTTP server for an immutable, public test clip. No filesystem access. */
public final class ClipHttpServer implements AutoCloseable {
  private static final int READ_TIMEOUT_MS = 1500;
  private static final int MAX_REQUEST_LINE = 4096;
  private static final int MAX_HEADER_LINE = 4096;
  private static final int MAX_HEADER_BYTES = 16384;
  private static final int MAX_HEADERS = 64;
  private static final int WORKER_COUNT = 4;
  private static final int QUEUE_SIZE = 8;

  /** Safe protocol summary. start/end are inclusive asset offsets, or -1 when absent. */
  public static final class Event {
    public final String method;
    public final int status;
    public final long bytesSent;
    public final long start;
    public final long end;
    public final String outcome;
    public final boolean assetRequest;

    private Event(Request request) {
      method = request.method;
      status = request.status;
      bytesSent = request.bytesSent;
      start = request.start;
      end = request.end;
      outcome = request.outcome;
      assetRequest = request.assetRequest;
    }
  }

  private final byte[] content;
  private final InetAddress bindAddress;
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
  public ClipHttpServer(byte[] content, InetAddress bindAddress, Consumer<Event> listener) {
    this.content = Objects.requireNonNull(content, "content").clone();
    this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
    this.listener = Objects.requireNonNull(listener, "listener");
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
              }, "transport-clip-worker-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        server = socket;
        workers = pool;
        port = socket.getLocalPort();
        running.set(true);
        acceptThread = new Thread(() -> acceptConnections(socket, pool), "transport-clip-accept");
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
      if (!path.equals(request.target)) {
        throw new HttpProblem(404, "not_found");
      }
      if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
        throw new HttpProblem(405, "method_not_allowed");
      }
      request.assetRequest = true;
      requestCount.incrementAndGet();
      long[] selected = selectRange(range);
      request.start = selected[0];
      request.end = selected[1];
      request.status = range == null ? 200 : 206;
      long length = request.end < request.start ? 0 : request.end - request.start + 1;
      OutputStream output = connection.getOutputStream();
      writeHeaders(output, request.status, length, request.start, request.end);
      if ("GET".equals(request.method)) {
        int offset = (int) request.start;
        int remaining = (int) length;
        while (remaining > 0) {
          int amount = Math.min(16384, remaining);
          output.write(content, offset, amount);
          request.bytesSent += amount;
          bytesServed.addAndGet(amount);
          offset += amount;
          remaining -= amount;
        }
      }
      request.outcome = "served";
    } catch (HttpProblem invalid) {
      request.status = invalid.status;
      request.outcome = invalid.outcome;
      sendError(connection, request);
    } catch (SocketTimeoutException timeout) {
      request.status = 408;
      request.outcome = "read_timeout";
      sendError(connection, request);
    } catch (IOException disconnected) {
      request.outcome = running.get()
          ? (request.status == 0 ? "client_disconnected" : "write_failed") : "stopped";
    } catch (RuntimeException failure) {
      request.outcome = "server_error";
    } finally {
      connections.remove(connection);
      closeQuietly(connection);
      emit(request);
    }
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
        if (range != null) throw new HttpProblem(416, "range_not_satisfiable");
        range = value;
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
        .append("X-Content-Type-Options: nosniff\r\nContent-Length: ").append(length)
        .append("\r\n");
    if (status == 200 || status == 206 || status == 416) {
      headers.append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n");
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

  private void emit(Request request) {
    try {
      listener.accept(new Event(request));
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
