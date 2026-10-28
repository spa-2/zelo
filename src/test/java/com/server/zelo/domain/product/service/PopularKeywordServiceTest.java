package com.server.zelo.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.server.zelo.domain.product.dto.response.GetPopularKeywordResponse;
import com.server.zelo.domain.product.repository.PopularKeywordRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

@ExtendWith(MockitoExtension.class)
class PopularKeywordServiceTest {

    @Mock
    private PopularKeywordRepository popularKeywordRepository;

    @InjectMocks
    private PopularKeywordService popularKeywordService;

    @Test
    void 대소문자와_띄어쓰기를_맞춰서_센다() {
        // when
        popularKeywordService.record("  닭가슴살   샐러드 ");
        popularKeywordService.record("Ramen");

        // then
        then(popularKeywordRepository).should().increase(eq("닭가슴살 샐러드"), any());
        then(popularKeywordRepository).should().increase(eq("ramen"), any());
    }

    @Test
    void 빈_검색어는_세지_않는다() {
        // when
        popularKeywordService.record("   ");

        // then
        then(popularKeywordRepository).shouldHaveNoInteractions();
    }

    @Test
    void 레디스가_안_돼도_검색어_기록은_예외를_던지지_않는다() {
        // given
        willThrow(new QueryTimeoutException("timeout")).given(popularKeywordRepository).increase(anyString(), any());

        // when, then
        assertThatCode(() -> popularKeywordService.record("김치")).doesNotThrowAnyException();
    }

    @Test
    void 많이_검색한_순으로_순위를_붙인다() {
        // given
        given(popularKeywordRepository.findTop(any(), anyInt()))
                .willReturn(List.of(TypedTuple.of("김치", 5.0), TypedTuple.of("두부", 3.0)));

        // when
        List<GetPopularKeywordResponse> result = popularKeywordService.getPopularKeywords();

        // then
        assertThat(result).extracting(GetPopularKeywordResponse::rank, GetPopularKeywordResponse::keyword,
                GetPopularKeywordResponse::count).containsExactly(tuple(1, "김치", 5L), tuple(2, "두부", 3L));
    }

    @Test
    void 레디스가_안_되면_빈_목록을_준다() {
        // given
        given(popularKeywordRepository.findTop(any(), anyInt())).willThrow(new QueryTimeoutException("timeout"));

        // when
        List<GetPopularKeywordResponse> result = popularKeywordService.getPopularKeywords();

        // then
        assertThat(result).isEmpty();
    }
}