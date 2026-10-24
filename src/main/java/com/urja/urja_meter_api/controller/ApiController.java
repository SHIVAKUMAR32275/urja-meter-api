
package com.urja.urja_meter_api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.urja.urja_meter_api.client.PortalClient;
import com.urja.urja_meter_api.dto.MeterPageResponse;
import com.urja.urja_meter_api.service.MeterService;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ApiController {

    private final MeterService meterService;
    private final PortalClient portalClient;

    public ApiController(
            MeterService meterService,
            PortalClient portalClient) {
        this.meterService = meterService;
        this.portalClient = portalClient;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "urja-meter-api",
                "version", "1.0.0",
                "upstreamAuthenticated", portalClient.isAuthenticated()
        );
    }

    @GetMapping("/meters")
    public MeterPageResponse getMeters(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "Page must be at least 1")
            int page) {

        return meterService.getMeters(q, page);
    }

    @GetMapping("/meters/{meterId}")
    public JsonNode getMeterDetails(
            @PathVariable String meterId) {

        return meterService.getMeterDetails(meterId);
    }

    @GetMapping("/meters/{meterId}/energy")
    public JsonNode getMeterEnergy(
            @PathVariable String meterId) {

        return meterService.getMeterEnergy(meterId);
    }
}
