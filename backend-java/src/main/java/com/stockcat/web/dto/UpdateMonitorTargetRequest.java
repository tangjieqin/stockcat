package com.stockcat.web.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateMonitorTargetRequest(@NotNull Boolean enabled) {
}
