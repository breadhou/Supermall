package com.mall.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.module.product.entity.po.Category;
import com.mall.module.product.entity.vo.CategoryVO;
import com.mall.module.product.mapper.CategoryMapper;
import com.mall.module.product.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    public List<CategoryVO> getCategoryTree() {
        // 1. 一次查出全表，按 sort 升序
        List<Category> all = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSort)
        );

        // 2. 按 parentId 分组：key=父ID, value=子分类列表
        Map<Long, List<Category>> childrenMap = all.stream()
                .collect(Collectors.groupingBy(Category::getParentId));

        // 3. 从顶级（parentId=0）开始递归
        return buildTree(0L, childrenMap);
    }

    /**
     * 递归构造分类树。
     *
     * @param parentId    当前层级的父 ID
     * @param childrenMap 所有数据按 parentId 分组
     * @return 该父节点下的子分类列表，叶子节点返空列表
     */
    private List<CategoryVO> buildTree(Long parentId, Map<Long, List<Category>> childrenMap) {
        List<Category> children = childrenMap.getOrDefault(parentId, Collections.emptyList());

        return children.stream().map(cat -> {
            CategoryVO vo = new CategoryVO();
            vo.setId(cat.getId());
            vo.setName(cat.getName());
            vo.setLevel(cat.getLevel());
            vo.setSort(cat.getSort());
            vo.setChildren(buildTree(cat.getId(), childrenMap));
            return vo;
        }).collect(Collectors.toList());
    }
}
