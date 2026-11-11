package com.server.zelo.domain.review.repository;

import com.server.zelo.domain.review.entity.ProductReviewSummary;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, Long> {

    // 아직 AI 요약이 없고 리뷰가 충분히 쌓인 상품만 요약 대상으로 고른다
    List<ProductReviewSummary> findAllByContentIsNullAndReviewCountGreaterThanEqual(Integer minReviewCount);

    // 이미 요약이 있는 상품까지 다시 만들 때(프롬프트를 바꿨거나 리뷰가 쌓였을 때) 대상으로 고른다
    List<ProductReviewSummary> findAllByReviewCountGreaterThanEqual(Integer minReviewCount);
}
