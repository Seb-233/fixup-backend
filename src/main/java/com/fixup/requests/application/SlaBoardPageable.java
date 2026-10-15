package com.fixup.requests.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Pageable;

/** Keeps framework pagination inside application/infrastructure while exposing plain query metadata. */
@Schema(name = "Pageable")
public final class SlaBoardPageable {
    @Schema(hidden = true)
    private final Pageable delegate;

    public SlaBoardPageable(Pageable delegate) {
        this.delegate = delegate;
    }

    @Schema(minimum = "0")
    public int getPage() { return delegate.getPageNumber(); }

    @Schema(minimum = "1")
    public int getSize() { return delegate.getPageSize(); }

    public List<String> getSort() {
        return delegate.getSort().stream()
                .map(order -> order.getProperty() + "," + order.getDirection()).toList();
    }

    Pageable pageable() { return delegate; }
}
