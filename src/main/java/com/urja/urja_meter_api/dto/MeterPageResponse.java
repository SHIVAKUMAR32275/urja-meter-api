package com.urja.urja_meter_api.dto;

import java.util.List;

public record MeterPageResponse(
        List<MeterDto> data,
        int total,
        int page,
        int pageSize
) {
}