package com.mall.module.product.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class CategoryVO {

    private Long id;
    private String name;
    private Integer level;
    private Integer sort;
    private List<CategoryVO> children;  // 自己包含自己，形成树

}
