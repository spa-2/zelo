package com.server.zelo.domain.product.dto.response;

public record GetPopularKeywordResponse(
        Integer rank,
        String keyword,
        Long count
) {

    public static GetPopularKeywordResponse of(Integer rank, String keyword, Long count) {
        return new GetPopularKeywordResponse(rank, keyword, count);
    }
}