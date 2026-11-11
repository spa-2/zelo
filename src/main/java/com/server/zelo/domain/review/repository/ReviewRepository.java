package com.server.zelo.domain.review.repository;

import com.server.zelo.domain.review.entity.Review;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    // 요약 프롬프트에 상품 이름이 필요해서, 트랜잭션 밖에서도 읽을 수 있게 상품을 함께 가져온다
    @EntityGraph(attributePaths = "product")
    List<Review> findByProductIdOrderByIdDesc(Long productId, Limit limit);
}
