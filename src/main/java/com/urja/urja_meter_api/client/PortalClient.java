
package com.urja.urja_meter_api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urja.urja_meter_api.dto.MeterPageResponse;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PortalClient {

    private final String baseUrl;
    private final String username;
    private final String password;
    private final ObjectMapper objectMapper;
    private final CookieManager cookieManager;
    private final HttpClient client;

    private volatile boolean authenticated = false;

    public PortalClient(
            @Value("${urja.portal.base-url}") String baseUrl,
            @Value("${urja.portal.username}") String username,
            @Value("${urja.portal.password}") String password,
            ObjectMapper objectMapper
    ) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.username = username;
        this.password = password;
        this.objectMapper = objectMapper;

        this.cookieManager = new CookieManager();
        this.cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

        this.client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public synchronized void authenticate() {
        try {
            HttpRequest loginPageRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "text/html")
                    .GET()
                    .build();

            client.send(
                    loginPageRequest,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );

            String formBody = "email=" + encode(username)
                    + "&password=" + encode(password);

            HttpRequest loginRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header(
                            "Content-Type",
                            "application/x-www-form-urlencoded"
                    )
                    .header("Accept", "application/json")
                    .header("Origin", baseUrl)
                    .header("Referer", baseUrl + "/login")
                    .header("x-sveltekit-action", "true")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> loginResponse = client.send(
                    loginRequest,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );

            if (loginResponse.statusCode() < 200
                    || loginResponse.statusCode() >= 300) {
                authenticated = false;

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Portal login failed with HTTP status "
                                + loginResponse.statusCode()
                );
            }

            JsonNode json;

            try {
                json = objectMapper.readTree(loginResponse.body());
            } catch (Exception e) {
                authenticated = false;

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Portal login returned an unexpected response."
                );
            }

            String type = json.path("type").asText("");
            int status = json.path("status").asInt(0);

            if ("redirect".equals(type)
                    && status >= 300
                    && status < 400) {
                authenticated = true;
                return;
            }

            authenticated = false;

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Portal login was not confirmed by the portal."
            );

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            authenticated = false;

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Portal login was interrupted."
            );

        } catch (IOException e) {
            authenticated = false;

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to communicate with the portal login endpoint."
            );
        }
    }

    public MeterPageResponse getMeters(String query, int page) {
        if (page < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be greater than or equal to 1."
            );
        }

        String safeQuery = query == null ? "" : query;

        JsonNode json = getJson(
                "/portal/meters/search?q="
                        + encode(safeQuery)
                        + "&page=" + page
        );

        try {
            return objectMapper.treeToValue(
                    json,
                    MeterPageResponse.class
            );
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to parse the meter list response."
            );
        }
    }

    public JsonNode getMeterDetails(String meterId) {
        validateMeterId(meterId);

        JsonNode response = getJson(
                "/portal/meters/search?q="
                        + encode(meterId)
                        + "&page=1"
        );

        JsonNode data = response.path("data");

        if (data.isArray()) {
            for (JsonNode meter : data) {
                if (meterId.equals(meter.path("meterId").asText())) {
                    return meter;
                }
            }
        }

        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Meter not found: " + meterId
        );
    }

    public JsonNode getMeterEnergy(String meterId) {
        validateMeterId(meterId);

        return getJson(
                "/portal/meters/" + meterId + "/energy"
        );
    }

    public JsonNode getMeterGeo(String meterId) {
        validateMeterId(meterId);

        return getJson(
                "/portal/meters/" + meterId + "/geo"
        );
    }

    public HttpResponse<byte[]> getExport(int page) {
        if (page < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be greater than or equal to 1."
            );
        }

        try {
            if (!authenticated) {
                authenticate();
            }

            // Fetch the signing secret from the portal for this session.
            JsonNode keysResponse = getJson("/portal/keys");

            String signingSecret = keysResponse
                    .path("data")
                    .path("signingSecret")
                    .asText("");

            if (signingSecret.isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Portal did not provide a signing secret."
                );
            }

            String path = "/portal/export";
            String query = "page=" + page;
            String timestamp = String.valueOf(
                    Instant.now().getEpochSecond()
            );

            String signature = generateSignature(
                    "GET",
                    path,
                    query,
                    timestamp,
                    signingSecret
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            baseUrl + path + "?" + query
                    ))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "*/*")
                    .header("Referer", baseUrl + "/transformers")
                    .header("x-timestamp", timestamp)
                    .header("x-signature", signature)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                String body = new String(
                        response.body(),
                        StandardCharsets.UTF_8
                );

                System.err.println(
                        "Export failed with HTTP status "
                                + response.statusCode()
                                + ": " + sanitizeResponse(body)
                );

                if (response.statusCode() == 401
                        || response.statusCode() == 403) {
                    authenticated = false;
                }

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Portal export failed with HTTP status "
                                + response.statusCode()
                );
            }

            return response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Export request was interrupted."
            );

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to communicate with the portal export endpoint."
            );

        } catch (GeneralSecurityException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to generate the export request signature."
            );
        }
    }

    private JsonNode getJson(String path) {
        try {
            if (!authenticated) {
                authenticate();
            }

            HttpResponse<String> response = sendJsonRequest(path);

            if (response.statusCode() == 401
                    || response.statusCode() == 403
                    || response.uri().getPath().equals("/login")) {
                authenticated = false;
                authenticate();
                response = sendJsonRequest(path);
            }

            if (response.statusCode() == 404) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Requested portal resource was not found."
                );
            }

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Portal request failed with HTTP status "
                                + response.statusCode()
                );
            }

            return objectMapper.readTree(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Portal request was interrupted."
            );

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to read the portal response."
            );
        }
    }

    private HttpResponse<String> sendJsonRequest(String path)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Referer", baseUrl + "/transformers")
                .GET()
                .build();

        return client.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8
                )
        );
    }

    private String generateSignature(
            String method,
            String path,
            String query,
            String timestamp,
            String signingSecret
    ) throws GeneralSecurityException {

        String message = String.join(
                "\n",
                method,
                path,
                query,
                timestamp
        );

        Mac mac = Mac.getInstance("HmacSHA256");

        SecretKeySpec key = new SecretKeySpec(
                signingSecret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );

        mac.init(key);

        byte[] signature = mac.doFinal(
                message.getBytes(StandardCharsets.UTF_8)
        );

        return HexFormat.of().formatHex(signature);
    }

    private String encode(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private void validateMeterId(String meterId) {
        if (meterId == null
                || !meterId.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid meter ID."
            );
        }
    }

    private String sanitizeResponse(String body) {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }

        String sanitized = body
                .replaceAll(
                        "(?i)(password|token|cookie|authorization|signingSecret)"
                                + "\\s*[\"']?\\s*[:=]\\s*[\"']?"
                                + "[^\"',}\\s]+",
                        "$1=[REDACTED]"
                )
                .replaceAll("[\\r\\n\\t]+", " ");

        return sanitized.substring(
                0,
                Math.min(sanitized.length(), 500)
        );
    }
}
