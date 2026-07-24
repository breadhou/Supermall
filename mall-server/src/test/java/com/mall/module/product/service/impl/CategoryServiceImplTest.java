package com.mall.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.module.product.entity.po.Category;
import com.mall.module.product.entity.vo.CategoryVO;
import com.mall.module.product.mapper.CategoryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryMapper categoryMapper;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    // ==================== 空数据库 ====================

    @Test
    void getCategoryTree_shouldReturnEmptyList_whenNoCategories() {
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertNotNull(tree);
        assertTrue(tree.isEmpty());
    }

    // ==================== 单个根节点 ====================

    @Test
    void getCategoryTree_shouldReturnSingleRoot_whenOneTopLevelCategory() {
        Category cat = buildCategory(1L, "电子产品", 0L, 1, 1);
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cat));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertEquals(1, tree.size());
        CategoryVO root = tree.get(0);
        assertEquals(1L, root.getId());
        assertEquals("电子产品", root.getName());
        assertEquals(1, root.getLevel());
        assertNotNull(root.getChildren());
        assertTrue(root.getChildren().isEmpty());
    }

    // ==================== 多个根节点，验证排序 ====================

    @Test
    void getCategoryTree_shouldReturnMultipleRootsSortedBySort() {
        Category cat1 = buildCategory(1L, "电子产品", 0L, 1, 2);
        Category cat2 = buildCategory(2L, "服装", 0L, 1, 1);
        // DB 返回时已按 sort 升序排列
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cat2, cat1));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertEquals(2, tree.size());
        assertEquals("服装", tree.get(0).getName());
        assertEquals("电子产品", tree.get(1).getName());
    }

    // ==================== 两级树 ====================

    @Test
    void getCategoryTree_shouldBuildTwoLevelTree() {
        Category root = buildCategory(1L, "电子产品", 0L, 1, 1);
        Category child1 = buildCategory(2L, "手机", 1L, 2, 1);
        Category child2 = buildCategory(3L, "电脑", 1L, 2, 2);

        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(root, child1, child2));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertEquals(1, tree.size());
        CategoryVO rootVO = tree.get(0);
        assertEquals("电子产品", rootVO.getName());

        List<CategoryVO> children = rootVO.getChildren();
        assertEquals(2, children.size());
        assertEquals("手机", children.get(0).getName());
        assertEquals("电脑", children.get(1).getName());

        // 叶子节点 children 为空列表
        assertTrue(children.get(0).getChildren().isEmpty());
        assertTrue(children.get(1).getChildren().isEmpty());
    }

    // ==================== 三级树 ====================

    @Test
    void getCategoryTree_shouldBuildThreeLevelTree() {
        Category root = buildCategory(1L, "电子产品", 0L, 1, 1);
        Category child = buildCategory(2L, "手机", 1L, 2, 1);
        Category grandchild1 = buildCategory(3L, "安卓", 2L, 3, 1);
        Category grandchild2 = buildCategory(4L, "苹果", 2L, 3, 2);

        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(root, child, grandchild1, grandchild2));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertEquals(1, tree.size());

        // 第二层
        List<CategoryVO> level2 = tree.get(0).getChildren();
        assertEquals(1, level2.size());
        assertEquals("手机", level2.get(0).getName());

        // 第三层
        List<CategoryVO> level3 = level2.get(0).getChildren();
        assertEquals(2, level3.size());
        assertEquals("安卓", level3.get(0).getName());
        assertEquals("苹果", level3.get(1).getName());

        // 第三层是叶子
        assertTrue(level3.get(0).getChildren().isEmpty());
        assertTrue(level3.get(1).getChildren().isEmpty());
    }

    // ==================== 多根多级 ====================

    @Test
    void getCategoryTree_shouldBuildMultipleRootsWithChildren() {
        Category root1 = buildCategory(1L, "电子产品", 0L, 1, 1);
        Category child1 = buildCategory(3L, "手机", 1L, 2, 1);
        Category root2 = buildCategory(2L, "服装", 0L, 1, 2);
        Category child2 = buildCategory(4L, "男装", 2L, 2, 1);

        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(root1, child1, root2, child2));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        assertEquals(2, tree.size());
        assertEquals("电子产品", tree.get(0).getName());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("手机", tree.get(0).getChildren().get(0).getName());

        assertEquals("服装", tree.get(1).getName());
        assertEquals(1, tree.get(1).getChildren().size());
        assertEquals("男装", tree.get(1).getChildren().get(0).getName());
    }

    // ==================== 只有子节点没有根节点的情况（孤立节点） ====================

    @Test
    void getCategoryTree_shouldIgnoreOrphanNodes() {
        // parentId=99 的分类，其父节点不存在
        Category orphan = buildCategory(1L, "孤立分类", 99L, 2, 1);
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(orphan));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        // 从 parentId=0 开始构建，孤立节点不会出现在树中
        assertTrue(tree.isEmpty());
    }

    // ==================== VO 字段完整性 ====================

    @Test
    void getCategoryTree_shouldSetAllVoFields() {
        Category cat = buildCategory(1L, "测试分类", 0L, 1, 5);
        when(categoryMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(cat));

        List<CategoryVO> tree = categoryService.getCategoryTree();

        CategoryVO vo = tree.get(0);
        assertEquals(1L, vo.getId());
        assertEquals("测试分类", vo.getName());
        assertEquals(1, vo.getLevel());
        assertEquals(5, vo.getSort());
        assertNotNull(vo.getChildren());
    }

    // ==================== 辅助方法 ====================

    private static Category buildCategory(Long id, String name, Long parentId, Integer level, Integer sort) {
        Category cat = new Category();
        cat.setId(id);
        cat.setName(name);
        cat.setParentId(parentId);
        cat.setLevel(level);
        cat.setSort(sort);
        return cat;
    }
}
