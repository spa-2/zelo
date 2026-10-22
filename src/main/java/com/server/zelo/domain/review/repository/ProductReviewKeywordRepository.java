package com.server.zelo.domain.review.repository;

import com.server.zelo.domain.product.entity.Keyword;
import com.server.zelo.domain.review.entity.ProductReviewKeyword;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductReviewKeywordRepository extends JpaRepository<ProductReviewKeyword, Long> {

    // 후보 상품 전체의 언급 수를 한 번에 가져와 상품마다 따로 조회하지 않는다
    List<ProductReviewKeyword> findAllByProductIdInAndKeywordIn(Collection<Long> productIds, Collection<Keyword> keywords);
}
