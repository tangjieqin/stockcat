package com.stockcat.web.dto;

import java.util.List;

public record RefreshSummary(int requested, int succeeded, int failed, List<RefreshResult> results) {
}
