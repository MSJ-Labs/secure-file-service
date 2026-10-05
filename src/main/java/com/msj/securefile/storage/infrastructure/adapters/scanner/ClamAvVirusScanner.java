package com.msj.securefile.storage.infrastructure.adapters.scanner;

import com.msj.securefile.storage.application.command.recordverdict.ScanVerdict;
import com.msj.securefile.storage.application.port.out.ScanExecutionException;
import com.msj.securefile.storage.application.port.out.ScannerUnavailableException;
import com.msj.securefile.storage.application.port.out.VirusScanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a stream with clamd over its INSTREAM command: the command, then the body in chunks each announced by its length
 * on four bytes, then a chunk of length zero. The body goes out as it is read, never held whole in memory. A clamd that
 * cannot be reached is an outage, which says nothing about the file; any failure once connected is a failed scan.
 */
@Component
@RequiredArgsConstructor
public class ClamAvVirusScanner implements VirusScanner {

    // Well under the stream limit clamd is configured with, and large enough for few system calls.
    private static final int MAX_CHUNK_BYTES = 64 * 1024;
    private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern INFECTED = Pattern.compile("^stream: (.+) FOUND$");
    private static final String CLEAN = "stream: OK";

    private final ClamAvSettings settings;

    @Override
    public ScanVerdict scan(InputStream content) {
        try (Socket socket = connect()) {
            socket.setSoTimeout((int) settings.readTimeout().toMillis());
            send(content, socket);
            return interpret(readAnswer(socket));
        } catch (IOException e) {
            // Reading the content, writing to clamd or waiting for its answer failed: the scan did not complete.
            throw new ScanExecutionException(e);
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(settings.host(), settings.port()),
                    (int) settings.connectTimeout().toMillis());
            return socket;
        } catch (IOException e) {
            socket.close();
            // Refused, unreachable or too slow to answer: nothing is known about the file.
            throw new ScannerUnavailableException(e);
        }
    }

    private void send(InputStream content, Socket socket) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        out.write(COMMAND);
        byte[] chunk = new byte[MAX_CHUNK_BYTES];
        int read;
        while ((read = content.read(chunk)) != -1) {
            if (read > 0) {
                out.writeInt(read);
                out.write(chunk, 0, read);
            }
        }
        out.writeInt(0);
        out.flush();
    }

    // clamd ends its answer to a command prefixed by z with a NUL byte.
    private String readAnswer(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) > 0) {
            answer.write(next);
        }
        if (answer.size() == 0) {
            throw new IOException("clamd closed the connection without an answer");
        }
        return answer.toString(StandardCharsets.US_ASCII).trim();
    }

    private ScanVerdict interpret(String answer) {
        if (CLEAN.equals(answer)) {
            return new ScanVerdict.Clean();
        }
        Matcher infected = INFECTED.matcher(answer);
        if (infected.matches()) {
            return new ScanVerdict.Infected(infected.group(1));
        }
        // An error of clamd (size limit, internal error) or something that is not its protocol.
        throw new ScanExecutionException(new IllegalStateException("clamd answered: " + answer));
    }
}
