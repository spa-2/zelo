package com.server.zelo.domain.user.service;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserBlockedCategory;
import com.server.zelo.domain.user.repository.UserBlockedCategoryRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserBlockedCategoryService {

    private final UserBlockedCategoryRepository userBlockedCategoryRepository;

    @Transactional(readOnly = true)
    public List<UserBlockedCategory> findBlockedCategories(Long userId) {
        return userBlockedCategoryRepository.findAllByUser_IdOrderByIdAsc(userId);
    }

    // 견적에서 상품을 고를 때 씀
    @Transactional(readOnly = true)
    public Set<Long> findBlockedCategoryIds(Long userId) {
        return findBlockedCategories(userId).stream()
                .map(blocked -> blocked.getCategory().getId())
                .collect(Collectors.toSet());
    }

    @Transactional
    public UserBlockedCategory blockCategory(User user, Category category) {
        if (userBlockedCategoryRepository.existsByUser_IdAndCategory_Id(user.getId(), category.getId())) {
            throw new BusinessException(ErrorCode.ALREADY_BLOCKED_CATEGORY);
        }
        // 위에서 확인한 사이에 같은 요청이 먼저 들어온 경우는 (user_id, category_id) 유니크 제약에서 걸림
        try {
            return userBlockedCategoryRepository.saveAndFlush(UserBlockedCategory.of(user, category));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_BLOCKED_CATEGORY);
        }
    }

    // 안 막혀 있던 걸 풀어도 결과는 같아서 예외 없이 끝냄
    @Transactional
    public void unblockCategory(Long userId, Long categoryId) {
        userBlockedCategoryRepository.deleteByUser_IdAndCategory_Id(userId, categoryId);
    }
}