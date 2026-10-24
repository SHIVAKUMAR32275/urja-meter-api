package com.urja.urja_meter_api.dto;

public record MeterDto(
        String meterId,
        String serialNo,
        String make,
        String phaseType,
        String installStatus,
        String dtCode
) {
}