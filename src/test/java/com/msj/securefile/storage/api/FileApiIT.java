package com.msj.securefile.storage.api;

import com.msj.securefile.storage.application.port.out.Actor;
import com.msj.securefile.storage.application.port.out.FileRepository;
import com.msj.securefile.storage.application.port.out.FileStoragePort;
import com.msj.securefile.storage.domain.file.SecureFile;
import com.msj.securefile.storage.domain.file.valueobject.FileId;
import com.msj.securefile.support.LocalStackTestS3;
import com.msj.securefile.support.PostgresTestDatabase;
import io.hypersistence.tsid.TSID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storage API through the real HTTP stack, PostgreSQL and LocalStack S3. The scan itself is out of scope here
 * (no worker runs): the tests that need a CLEAN file move it there with the same calls the worker makes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=test-only-secret-test-only-secret-test-only-secret-test-only-secret-0123456789",
                "jwt.access-token-expiration-ms=900000",
                "jwt.refresh-token-expiration-ms=604800000",
                "app.storage.s3.access-key=test",
                "app.storage.s3.secret-key=test",
                "app.upload.max-size-bytes=1024",
                // No scan worker here: nothing may move a file out of PENDING behind the tests' back.
                "app.worker.enabled=false"
        })
class FileApiIT {

    private static final String PASSWORD = "password123";
    private static final String CONTENT = "hello secure world";
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
        registry.add("app.storage.s3.endpoint",
                () -> LocalStackTestS3.client().serviceClientConfiguration().endpointOverride().orElseThrow().toString());
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FileRepository fileRepository;
    @Autowired
    private FileStoragePort fileStoragePort;

    private final HttpClient http = HttpClient.newHttpClient();

    // ----- helpers ---------------------------------------------------------------------------------------------

    private HttpResponse<String> send(HttpRequest.Builder request, String cookie) throws Exception {
        if (cookie != null) request.header("Cookie", cookie);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> upload(String name, String content, String cookie) throws Exception {
        return send(request("/api/v1/files?name=" + name)
                .PUT(HttpRequest.BodyPublishers.ofString(content)), cookie);
    }

    private HttpResponse<String> list(String cookie) throws Exception {
        return send(request("/api/v1/files").GET(), cookie);
    }

    private HttpResponse<String> download(String id, String cookie) throws Exception {
        return send(request("/api/v1/files/" + id + "/content").GET(), cookie);
    }

    private static String idOf(HttpResponse<String> response) {
        Matcher matcher = ID.matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    // Registers a fresh user and returns the cookie header of its session.
    private String newSession() throws Exception {
        String username = "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String register = """
                {"username":"%s","email":"%s@example.com","password":"%s","firstName":"Jane","lastName":"Doe"}"""
                .formatted(username, username, PASSWORD);
        assertThat(http.send(request("/api/v1/auth/register").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(register)).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(201);
        HttpResponse<String> login = http.send(request("/api/v1/auth/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"username":"%s","password":"%s"}""".formatted(username, PASSWORD))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        Map<String, String> cookies = new LinkedHashMap<>();
        login.headers().allValues("set-cookie").forEach(header -> {
            String pair = header.substring(0, header.indexOf(';'));
            cookies.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        });
        StringBuilder cookie = new StringBuilder();
        cookies.forEach((name, value) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(name).append('=').append(value));
        return cookie.toString();
    }

    // What the worker does once the scan found nothing, with the same ports.
    private void markClean(String id) {
        FileId fileId = new FileId(TSID.from(id));
        SecureFile file = fileRepository.findByIdForScan(fileId).orElseThrow();
        file.startScan(Instant.now());
        file.markClean(Instant.now());
        fileRepository.save(file, new Actor.System());
        fileStoragePort.promote(fileId);
    }

    // ----- tests -----------------------------------------------------------------------------------------------

    @Test
    void files_requireAuthentication() throws Exception {
        assertThat(upload("report.txt", CONTENT, null).statusCode()).isEqualTo(401);
        assertThat(list(null).statusCode()).isEqualTo(401);
        assertThat(download("0ABC", null).statusCode()).isEqualTo(401);
    }

    @Test
    void upload_acceptsTheFileAndItAppearsInTheListWithItsStatus() throws Exception {
        String session = newSession();

        HttpResponse<String> uploaded = upload("report.txt", CONTENT, session);

        // Accepted, not created and served: the scan comes next.
        assertThat(uploaded.statusCode()).isEqualTo(202);
        String id = idOf(uploaded);
        HttpResponse<String> listed = list(session);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains(id, "report.txt", "PENDING");
    }

    @Test
    void list_showsOnlyTheFilesOfTheCaller() throws Exception {
        String owner = newSession();
        String stranger = newSession();
        upload("mine.txt", CONTENT, owner);

        HttpResponse<String> listed = list(stranger);

        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).doesNotContain("mine.txt");
    }

    @Test
    void upload_withoutAContentLength_isLengthRequired() throws Exception {
        String session = newSession();
        HttpRequest.Builder chunked = request("/api/v1/files?name=report.txt").PUT(HttpRequest.BodyPublishers
                .ofInputStream(() -> new ByteArrayInputStream(CONTENT.getBytes(StandardCharsets.UTF_8))));

        // The declared size drives the storage cap: a body of unknown length is refused up front.
        assertThat(send(chunked, session).statusCode()).isEqualTo(411);
    }

    @Test
    void upload_largerThanTheConfiguredLimit_isRejected() throws Exception {
        String session = newSession();

        HttpResponse<String> response = upload("big.bin", "x".repeat(2_048), session);

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void download_beforeTheScanIsDone_isAConflict() throws Exception {
        String session = newSession();
        String id = idOf(upload("report.txt", CONTENT, session));

        HttpResponse<String> response = download(id, session);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).doesNotContain(CONTENT);
    }

    @Test
    void download_ofAFileOfSomeoneElse_behavesAsNotFound() throws Exception {
        String owner = newSession();
        String stranger = newSession();
        String id = idOf(upload("secret.txt", CONTENT, owner));
        markClean(id);

        assertThat(download(id, stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void download_ofAnUnknownOrMalformedId_isNotFound() throws Exception {
        String session = newSession();

        HttpResponse<String> unknown = download("0000000000000", session);
        HttpResponse<String> malformed = download("not-an-id", session);

        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(404);
    }

    @Test
    void download_ofACleanFile_streamsItAsAnAttachment() throws Exception {
        String session = newSession();
        String id = idOf(upload("report.txt", CONTENT, session));
        markClean(id);

        HttpResponse<String> response = download(id, session);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CONTENT);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                .startsWith("attachment").contains("report.txt");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/octet-stream");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }
}
