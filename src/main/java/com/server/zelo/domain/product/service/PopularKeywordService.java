package com.server.zelo.domain.product.service;

import com.server.zelo.domain.product.dto.response.GetPopularKeywordResponse;
import com.server.zelo.domain.product.repository.PopularKeywordRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

// 인기 검색어는 없어도 검색·화면이 돌아가야 해서 Redis가 안 되면 로그만 남김
@Slf4j
@Service
@RequiredArgsConstructor
public class PopularKeywordService {

    private static final int LIMIT = 10;

    private final PopularKeywordRepository popularKeywordRepository;

    public void record(String keyword) {
        String normalized = normalize(keyword);
        if (normalized.isEmpty()) {
            return;
        }
        try {
            popularKeywordRepository.increase(normalized, LocalDateTime.now());
        } catch (DataAccessException e) {
            log.warn("인기 검색어 기록 실패 error={}", e.getMessage());
        }
    }

    public List<GetPopularKeywordResponse> getPopularKeywords() {
        try {
            List<TypedTuple<String>> top = popularKeywordRepository.findTop(LocalDateTime.now(), LIMIT);
            return IntStream.range(0, top.size())
                    .mapToObj(i -> GetPopularKeywordResponse.of(i + 1, top.get(i).getValue(), top.get(i).getScore().longValue()))
                    .toList();
        } catch (DataAccessException e) {
            log.warn("인기 검색어 조회 실패 error={}", e.getMessage());
            return List.of();
        }
    }

    // 대소문자나 띄어쓰기만 다른 건 같은 검색어로 셈
    private String normalize(String keyword) {
        return keyword == null ? "" : keyword.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}