package com.server.zelo.domain.product.dto.response;

import com.server.zelo.domain.product.entity.Category;
import java.util.List;

public record GetCategoryResponse(
        Long categoryId,
        String name,
        List<SubCategory> children
) {

    public static GetCategoryResponse from(Category category, List<Category> children) {
        return new GetCategoryResponse(
                category.getId(),
                category.getName(),
                children.stream().map(SubCategory::from).toList());
    }

    public record SubCategory(
            Long categoryId,
            String name
    ) {

        public static SubCategory from(Category category) {
            return new SubCategory(category.getId(), category.getName());
        }
    }
}