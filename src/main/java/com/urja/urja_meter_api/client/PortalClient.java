
package com.urja.urja_meter_api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urja.urja_meter_api.dto.MeterPageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class PortalClient {

    private final String baseUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile boolean authenticated;

    public PortalClient(
            @Value("${urja.portal.base-url}") String baseUrl,
            @Value("${urja.portal.username}") String username,
            @Value("${urja.portal.password:}") String password,
            ObjectMapper objectMapper) {

        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;

        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public synchronized void authenticate() {
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Portal password is not configured."
            );
        }

        authenticated = false;

        try {
            HttpRequest loginPageRequest = HttpRequest.newBuilder()
                    .uri(uri("/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/html")
                    .GET()
                    .build();

            HttpResponse<String> loginPage = send(loginPageRequest);

            if (loginPage.statusCode() != 200) {
                throw upstreamError(
                        "Unable to load the portal login page. HTTP "
                                + loginPage.statusCode()
                );
            }

            String form = "email=" + encode(username)
                    + "&password=" + encode(password);

            HttpRequest loginRequest = HttpRequest.newBuilder()
                    .uri(uri("/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header(
                            "Content-Type",
                            "application/x-www-form-urlencoded"
                    )
                    .header("Accept", "application/json")
                    .header("Origin", baseUrl)
                    .header("Referer", baseUrl + "/login")
                    .header("x-sveltekit-action", "true")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> response = send(loginRequest);

            boolean redirectInstructionFound = false;
            String redirectLocation = "";

            if (response.body() != null
                    && !response.body().isBlank()) {
                try {
                    JsonNode json = objectMapper.readTree(response.body());

                    redirectLocation = json.path("location").asText("");

                    redirectInstructionFound =
                            response.statusCode() == 200
                                    && "redirect".equals(
                                    json.path("type").asText()
                            )
                                    && json.path("status").asInt() >= 300
                                    && json.path("status").asInt() < 400
                                    && !redirectLocation.isBlank()
                                    && redirectLocation.startsWith("/")
                                    && !redirectLocation.startsWith("//");
                } catch (Exception ignored) {
                    redirectInstructionFound = false;
                }
            }

            if (response.statusCode() >= 400
                    || !redirectInstructionFound) {

                System.err.println(
                        "Portal login HTTP status: "
                                + response.statusCode()
                );

                System.err.println(
                        "Portal login response: "
                                + sanitizeResponse(response.body())
                );

                throw upstreamError(
                        "Portal login failed. Check the credentials and "
                                + "the sanitized application diagnostics."
                );
            }

            authenticated = true;

            System.out.println(
                    "Portal login returned redirect instruction: "
                            + redirectLocation
            );

        } catch (IOException e) {
            authenticated = false;
            throw upstreamError("Could not connect to the portal.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            authenticated = false;
            throw upstreamError("Portal login was interrupted.");
        }
    }

    public MeterPageResponse getMeters(String query, int page) {
        String path = "/portal/meters/search?q="
                + encode(query == null ? "" : query)
                + "&page=" + page;

        JsonNode json = getJson(path);

        try {
            return objectMapper.treeToValue(
                    json,
                    MeterPageResponse.class
            );
        } catch (Exception e) {
            throw upstreamError(
                    "The portal returned an unexpected meter-list response."
            );
        }
    }

    public JsonNode getMeterDetails(String meterId) {
        MeterPageResponse response = getMeters(meterId, 1);

        if (response.data() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The portal returned an invalid meter-list response."
            );
        }

        return response.data()
                .stream()
                .filter(meter -> meterId.equals(meter.meterId()))
                .findFirst()
                .map(meter ->
                        objectMapper.<JsonNode>valueToTree(meter)
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Meter not found: " + meterId
                ));
    }

    public JsonNode getMeterEnergy(String meterId) {
        return getJson(
                "/portal/meters/" + encode(meterId) + "/energy"
        );
    }

    private JsonNode getJson(String path) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!authenticated) {
                authenticate();
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri(path))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = send(request);

                if (response.statusCode() == 401
                        || response.uri().getPath().equals("/login")) {
                    authenticated = false;
                    continue;
                }

                if (response.statusCode() == 404) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "The requested portal resource was not found."
                    );
                }

                if (response.statusCode() != 200) {
                    System.err.println(
                            "Portal data HTTP status: "
                                    + response.statusCode()
                    );

                    System.err.println(
                            "Portal data response: "
                                    + sanitizeResponse(response.body())
                    );

                    throw upstreamError(
                            "The portal request failed with HTTP "
                                    + response.statusCode() + "."
                    );
                }

                try {
                    return objectMapper.readTree(response.body());
                } catch (Exception e) {
                    throw upstreamError(
                            "The portal returned invalid JSON."
                    );
                }

            } catch (IOException e) {
                throw upstreamError(
                        "Could not retrieve data from the portal."
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw upstreamError(
                        "The portal request was interrupted."
                );
            }
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Portal authentication failed or the session expired."
        );
    }

    private HttpResponse<String> send(HttpRequest request)
            throws IOException, InterruptedException {

        return httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8
                )
        );
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private String encode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private String sanitizeResponse(String body) {
        if (body == null) {
            return "";
        }

        return body
                .replaceAll(
                        "(?i)(\"password\"\\s*:\\s*\")[^\"]*(\")",
                        "$1[REDACTED]$2"
                )
                .replaceAll(
                        "(?i)(\"(?:token|session_token|access_token|"
                                + "refresh_token|cookie)\"\\s*:\\s*\")[^\"]*(\")",
                        "$1[REDACTED]$2"
                );
    }

    private ResponseStatusException upstreamError(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                message
        );
    }

    public boolean isAuthenticated() {
        return authenticated;
    }
}
