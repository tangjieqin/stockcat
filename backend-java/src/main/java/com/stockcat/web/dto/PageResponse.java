package com.stockcat.web.dto;

import java.util.List;

/**
 * 统一分页结构，对应 docs/API.md §2 的 {items, page, size, total}。
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
}
