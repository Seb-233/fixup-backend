package com.fixup.requests.application;

import java.util.List;

/** Snapshot of the existing page JSON; no persistence types cross into web. */
public record Page<T>(int totalPages, long totalElements, int size, List<T> content, int number,
        SortObject sort, int numberOfElements, boolean last, boolean first,
        PageableObject pageable, boolean empty) {

    static <T> Page<T> from(org.springframework.data.domain.Page<T> page) {
        var pageable = page.getPageable();
        var sort = page.getSort();
        return new Page<>(page.getTotalPages(), page.getTotalElements(), page.getSize(), page.getContent(),
                page.getNumber(), new SortObject(sort.isEmpty(), sort.isUnsorted(), sort.isSorted()),
                page.getNumberOfElements(), page.isLast(), page.isFirst(),
                new PageableObject(pageable.getOffset(),
                        new SortObject(pageable.getSort().isEmpty(), pageable.getSort().isUnsorted(),
                                pageable.getSort().isSorted()),
                        pageable.getPageSize(), pageable.getPageNumber(), pageable.isUnpaged(), pageable.isPaged()),
                page.isEmpty());
    }

    public record SortObject(boolean empty, boolean unsorted, boolean sorted) {}

    public record PageableObject(long offset, SortObject sort, int pageSize, int pageNumber,
            boolean unpaged, boolean paged) {}
}
