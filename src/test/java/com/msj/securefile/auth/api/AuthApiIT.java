package com.msj.securefile.auth.api;

import com.msj.securefile.support.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole authentication flow through the real HTTP stack: servlet filters, Spring Security, controllers,
 * handlers and the jOOQ adapters against PostgreSQL. Tokens travel in cookies, as in production.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=test-only-secret-test-only-secret-test-only-secret-test-only-secret-0123456789",
                "jwt.access-token-expiration-ms=900000",
                "jwt.refresh-token-expiration-ms=604800000"
        })
class AuthApiIT {

    private static final String PASSWORD = "password123";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", PostgresTestDatabase::username);
        registry.add("spring.datasource.password", PostgresTestDatabase::password);
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // ----- helpers ---------------------------------------------------------------------------------------------

    private HttpResponse<String> post(String path, String json, Map<String, String> cookies) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        addCookies(request, cookies);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMe(Map<String, String> cookies) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me")).GET();
        addCookies(request, cookies);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void addCookies(HttpRequest.Builder request, Map<String, String> cookies) {
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            cookies.forEach((name, value) -> header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
            request.header("Cookie", header.toString());
        }
    }

    /** The name=value pairs of every Set-Cookie header of a response. */
    private static Map<String, String> cookiesOf(HttpResponse<?> response) {
        Map<String, String> cookies = new LinkedHashMap<>();
        response.headers().allValues("set-cookie").forEach(header -> {
            String pair = header.substring(0, header.indexOf(';') < 0 ? header.length() : header.indexOf(';'));
            cookies.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        });
        return cookies;
    }

    private static String uniqueName() {
        return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String registerJson(String username) {
        return """
                {"username":"%s","email":"%s@example.com","password":"%s","firstName":"Jane","lastName":"Doe"}"""
                .formatted(username, username, PASSWORD);
    }

    private static String loginJson(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    private String registerUser() throws Exception {
        String username = uniqueName();
        assertThat(post("/api/v1/auth/register", registerJson(username), Map.of()).statusCode()).isEqualTo(201);
        return username;
    }

    private Map<String, String> loginAs(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", loginJson(username, PASSWORD), Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        return cookiesOf(response);
    }

    // ----- register --------------------------------------------------------------------------------------------

    @Test
    void register_createsTheAccountAndNeverReturnsThePassword() throws Exception {
        String username = uniqueName();

        HttpResponse<String> response = post("/api/v1/auth/register", registerJson(username), Map.of());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body())
                .contains("\"username\":\"" + username + "\"")
                .contains("ROLE_USER")
                .doesNotContain("password")
                .doesNotContain(PASSWORD);
    }

    @Test
    void register_withAnExistingUsername_isAConflict() throws Exception {
        String username = registerUser();

        HttpResponse<String> response = post("/api/v1/auth/register", registerJson(username), Map.of());

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void register_withAnInvalidBody_isABadRequest() throws Exception {
        String invalid = """
                {"username":"","email":"not-an-email","password":"short"}""";

        HttpResponse<String> response = post("/api/v1/auth/register", invalid, Map.of());

        assertThat(response.statusCode()).isEqualTo(400);
        // The rejected fields are named, their values (a password) are not echoed back
        assertThat(response.body())
                .contains("\"field\":\"username\"", "\"field\":\"email\"", "\"field\":\"password\"")
                .doesNotContain("not-an-email", "\"short\"");
    }

    // ----- login -----------------------------------------------------------------------------------------------

    @Test
    void login_setsHttpOnlyAccessAndRefreshCookies() throws Exception {
        String username = registerUser();

        HttpResponse<String> response = post("/api/v1/auth/login", loginJson(username, PASSWORD), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"username\":\"" + username + "\"");
        assertThat(response.headers().allValues("set-cookie"))
                .hasSize(2)
                .allSatisfy(cookie -> assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict"))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("access_token="))
                .anySatisfy(cookie -> assertThat(cookie).startsWith("refresh_token="));
    }

    @Test
    void login_withAWrongPassword_isUnauthorized() throws Exception {
        String username = registerUser();

        assertThat(post("/api/v1/auth/login", loginJson(username, "wrong-password"), Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void login_withAnUnknownUser_isUnauthorizedToo() throws Exception {
        assertThat(post("/api/v1/auth/login", loginJson(uniqueName(), PASSWORD), Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void login_locksTheAccountAfterFiveFailures() throws Exception {
        String username = registerUser();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/v1/auth/login", loginJson(username, "wrong-password"), Map.of()).statusCode()).isEqualTo(401);
        }

        // Even the right password is refused while the account is locked
        assertThat(post("/api/v1/auth/login", loginJson(username, PASSWORD), Map.of()).statusCode()).isEqualTo(423);
    }

    // ----- current user ----------------------------------------------------------------------------------------

    @Test
    void me_withTheAccessCookie_returnsTheProfile() throws Exception {
        String username = registerUser();
        Map<String, String> cookies = loginAs(username);

        HttpResponse<String> response = getMe(Map.of("access_token", cookies.get("access_token")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"username\":\"" + username + "\"");
    }

    @Test
    void me_withoutCredentials_isUnauthorized() throws Exception {
        assertThat(getMe(Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void me_withAForgedToken_isUnauthorized() throws Exception {
        assertThat(getMe(Map.of("access_token", "not.a.jwt")).statusCode()).isEqualTo(401);
    }

    // ----- refresh and logout ----------------------------------------------------------------------------------

    @Test
    void refresh_withTheRefreshCookie_issuesANewAccessToken() throws Exception {
        Map<String, String> cookies = loginAs(registerUser());

        HttpResponse<String> response = post("/api/v1/auth/refresh", "", Map.of("refresh_token", cookies.get("refresh_token")));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(cookiesOf(response)).containsKey("access_token");
    }

    @Test
    void refresh_withoutARefreshCookie_isUnauthorized() throws Exception {
        assertThat(post("/api/v1/auth/refresh", "", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void logout_revokesTheRefreshToken() throws Exception {
        Map<String, String> cookies = loginAs(registerUser());

        HttpResponse<String> logout = post("/api/v1/auth/logout", "", cookies);
        HttpResponse<String> refreshAfterLogout = post("/api/v1/auth/refresh", "", Map.of("refresh_token", cookies.get("refresh_token")));

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(refreshAfterLogout.statusCode()).isEqualTo(401);
    }
}