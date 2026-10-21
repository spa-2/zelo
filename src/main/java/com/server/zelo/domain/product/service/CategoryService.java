package com.server.zelo.domain.product.service;

import com.server.zelo.domain.product.dto.response.GetCategoryResponse;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.product.repository.CategoryRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<GetCategoryResponse> getCategories() {
        // 카테고리가 몇십 개라 한 번에 다 가져와서 묶음 (대분류마다 조회하면 N+1)
        List<Category> categories = categoryRepository.findAllByOrderByIdAsc();

        Map<Long, List<Category>> childrenByParentId = categories.stream()
                .filter(category -> category.getParent() != null)
                .collect(Collectors.groupingBy(category -> category.getParent().getId()));

        return categories.stream()
                .filter(category -> category.getParent() == null)
                .map(parent -> GetCategoryResponse.from(parent,
                        childrenByParentId.getOrDefault(parent.getId(), List.of())))
                .toList();
    }
}