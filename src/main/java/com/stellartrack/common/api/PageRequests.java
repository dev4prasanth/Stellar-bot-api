package com.stellartrack.common.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Bounds client-supplied paging parameters so a request cannot ask for unbounded result sets. */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 200;

    private PageRequests() {
    }

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }
}
