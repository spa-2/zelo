package com.server.zelo.domain.product.dto.request;

import org.springframework.data.domain.Sort;

// 값이 같으면 id로 한 번 더 정렬해서 페이지 넘길 때 순서가 안 섞이게 함
public enum ProductSort {
    SALES(Sort.by(Sort.Order.desc("salesCount"), Sort.Order.desc("id"))),
    PRICE_ASC(Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id"))),
    PRICE_DESC(Sort.by(Sort.Order.desc("price"), Sort.Order.desc("id"))),
    LATEST(Sort.by(Sort.Order.desc("id")));

    private final Sort sort;

    ProductSort(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}