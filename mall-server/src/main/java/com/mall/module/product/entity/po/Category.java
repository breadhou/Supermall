package com.mall.module.product.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@TableName("category")
@Accessors(chain = true)
public class Category {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private Integer sort;
    private LocalDateTime createdAt;

}
