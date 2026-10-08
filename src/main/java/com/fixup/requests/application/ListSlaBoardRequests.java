package com.fixup.requests.application;

import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListSlaBoardRequests {
    private final RepairRequests requests;

    public ListSlaBoardRequests(RepairRequests requests) {
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public <T> Page<T> execute(SlaBoardPageable pageable, Function<RepairRequest, T> projection) {
        return Page.from(requests.findSlaBoardRequests(pageable.pageable()).map(projection));
    }
}
