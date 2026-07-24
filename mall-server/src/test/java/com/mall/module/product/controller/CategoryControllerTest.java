package com.mall.module.product.controller;

import com.mall.common.enums.ResultStatus;
import com.mall.common.result.Result;
import com.mall.module.product.entity.vo.CategoryVO;
import com.mall.module.product.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock
    private CategoryService categoryService;

    @InjectMocks
    private CategoryController categoryController;

    @Test
    void getCategories_shouldReturnTreeWithSuccessResult() {
        CategoryVO vo = new CategoryVO();
        vo.setId(1L);
        vo.setName("电子产品");
        vo.setLevel(1);
        vo.setSort(1);
        vo.setChildren(Collections.emptyList());

        when(categoryService.getCategoryTree()).thenReturn(List.of(vo));

        Result<List<CategoryVO>> result = categoryController.getCategories();

        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertEquals(0, result.getCode());

        List<CategoryVO> data = result.getData();
        assertNotNull(data);
        assertEquals(1, data.size());
        assertEquals("电子产品", data.get(0).getName());
    }

    @Test
    void getCategories_shouldReturnEmptyList_whenNoCategories() {
        when(categoryService.getCategoryTree()).thenReturn(Collections.emptyList());

        Result<List<CategoryVO>> result = categoryController.getCategories();

        assertNotNull(result);
        assertEquals(ResultStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
    }
}
