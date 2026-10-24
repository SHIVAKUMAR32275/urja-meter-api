
package com.urja.urja_meter_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.urja.urja_meter_api.client.PortalClient;
import com.urja.urja_meter_api.dto.MeterPageResponse;
import org.springframework.stereotype.Service;

@Service
public class MeterService {

    private final PortalClient portalClient;

    public MeterService(PortalClient portalClient) {
        this.portalClient = portalClient;
    }

    public MeterPageResponse getMeters(String query, int page) {
        if (page < 1) {
            throw new IllegalArgumentException(
                    "Page number must be greater than or equal to 1."
            );
        }

        return portalClient.getMeters(query, page);
    }

    public JsonNode getMeterDetails(String meterId) {
        return portalClient.getMeterDetails(validateMeterId(meterId));
    }

    public JsonNode getMeterEnergy(String meterId) {
        return portalClient.getMeterEnergy(validateMeterId(meterId));
    }

    private String validateMeterId(String meterId) {
        if (meterId == null || !meterId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid meter ID.");
        }

        return meterId;
    }
}
