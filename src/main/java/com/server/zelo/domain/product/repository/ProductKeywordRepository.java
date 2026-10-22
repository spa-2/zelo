package com.server.zelo.domain.product.repository;

import com.server.zelo.domain.product.entity.ProductKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductKeywordRepository extends JpaRepository<ProductKeyword, Long> {
}
