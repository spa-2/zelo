package com.server.zelo.domain.user.repository;

import com.server.zelo.domain.user.entity.UserBlockedCategory;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserBlockedCategoryRepository extends JpaRepository<UserBlockedCategory, Long> {

    // 목록에 카테고리 이름이 나가서 같이 가져옴
    @EntityGraph(attributePaths = {"category"})
    List<UserBlockedCategory> findAllByUser_IdOrderByIdAsc(Long userId);

    boolean existsByUser_IdAndCategory_Id(Long userId, Long categoryId);

    long deleteByUser_IdAndCategory_Id(Long userId, Long categoryId);
}