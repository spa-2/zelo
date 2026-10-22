package com.server.zelo.domain.review.repository;

import com.server.zelo.domain.review.entity.ProductReviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductReviewSummaryRepository extends JpaRepository<ProductReviewSummary, Long> {
}
