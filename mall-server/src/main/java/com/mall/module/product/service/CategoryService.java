package com.mall.module.product.service;

import com.mall.module.product.entity.vo.CategoryVO;

import java.util.List;

public interface CategoryService {

    /** 获取完整分类树（一次查库，内存递归组装） */
    List<CategoryVO> getCategoryTree();
}
