package com.fixup.properties.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(requiredProperties = {"items", "page", "size", "totalItems", "totalPages"})
public record CatalogPage(List<CatalogItem> items, int page, int size, long totalItems, int totalPages) {
}
