package com.msj.securefile.support;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A clamd that speaks just enough of the INSTREAM protocol to test a client: it reads the command and the chunks the
 * client sends, records them, then behaves as scripted. A real ClamAV takes minutes to load its signatures, which is
 * too slow for tests of the protocol.
 */
public final class FakeClamd implements AutoCloseable {

    /** What the client sent: the command, the body reassembled, and the length of every chunk. */
    public record Exchange(String command, byte[] body, List<Integer> chunkLengths) {
    }

    private enum Behavior {REPLY, STAY_SILENT, HANG_UP}

    private final ServerSocket server;
    private final CompletableFuture<Exchange> exchange = new CompletableFuture<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Behavior behavior;
    private final String response;

    private FakeClamd(Behavior behavior, String response) throws IOException {
        this.server = new ServerSocket(0);
        this.behavior = behavior;
        this.response = response;
        Thread.ofVirtual().start(this::serve);
    }

    /** Answers the stream with this text, ended by the NUL byte clamd uses for the commands prefixed by z. */
    public static FakeClamd replying(String response) {
        return start(Behavior.REPLY, response);
    }

    /** Reads the whole stream and then never answers, as a clamd that is stuck. */
    public static FakeClamd neverReplying() {
        return start(Behavior.STAY_SILENT, null);
    }

    /** Reads the whole stream and then closes the connection without a word. */
    public static FakeClamd hangingUp() {
        return start(Behavior.HANG_UP, null);
    }

    /** A port on which nobody listens: connecting to it is refused. */
    public static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FakeClamd start(Behavior behavior, String response) {
        try {
            return new FakeClamd(behavior, response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Waits for what the client sent, so a test does not read it before the client has finished. */
    public Exchange exchange() {
        try {
            return exchange.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The client sent nothing to the fake clamd", e);
        }
    }

    private void serve() {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String command = readCommand(in);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            List<Integer> chunkLengths = new ArrayList<>();
            int length;
            while ((length = in.readInt()) != 0) {
                chunkLengths.add(length);
                body.write(in.readNBytes(length));
            }
            exchange.complete(new Exchange(command, body.toByteArray(), chunkLengths));
            answer(socket);
        } catch (IOException e) {
            // The test closed the server or the client gave up: nothing more to do here.
            exchange.completeExceptionally(e);
        }
    }

    private static String readCommand(InputStream in) throws IOException {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) > 0) {
            command.write(next);
        }
        return command.toString(StandardCharsets.US_ASCII);
    }

    private void answer(Socket socket) throws IOException {
        switch (behavior) {
            case REPLY -> {
                OutputStream out = socket.getOutputStream();
                out.write((response + '\0').getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
            case STAY_SILENT -> {
                // Waits for the test to close the server, with a ceiling so a forgotten close cannot hang the build.
                try {
                    closed.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            case HANG_UP -> {
                // Leaving the try-with-resources closes the socket.
            }
        }
    }

    @Override
    public void close() {
        closed.countDown();
        try {
            server.close();
        } catch (IOException _) {
            // Nothing to recover: the test is over.
        }
    }
}
