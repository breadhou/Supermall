package com.mall.module.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.module.product.entity.po.Review;
import com.mall.module.product.entity.vo.ReviewVO;
import com.mall.module.product.mapper.ReviewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    @Mock
    private ReviewMapper reviewMapper;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    @Test
    void listByProductId_shouldReturnReviews_whenFound() {
        Review r1 = buildReview(1L, 10L, 5, "很好");
        Review r2 = buildReview(2L, 10L, 3, "一般");

        when(reviewMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(r1, r2));

        List<ReviewVO> reviews = reviewService.listByProductId(10L);

        assertEquals(2, reviews.size());
        assertEquals(5, reviews.get(0).getRating());
        assertEquals("很好", reviews.get(0).getContent());
        assertEquals(3, reviews.get(1).getRating());
    }

    @Test
    void listByProductId_shouldReturnEmptyList_whenNoReviews() {
        when(reviewMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        List<ReviewVO> reviews = reviewService.listByProductId(999L);

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty());
    }

    @Test
    void listByProductId_shouldMapAllFields() {
        Review review = buildReview(1L, 10L, 4, "不错");

        when(reviewMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(review));

        List<ReviewVO> reviews = reviewService.listByProductId(10L);

        ReviewVO vo = reviews.get(0);
        assertEquals(1L, vo.getId());
        assertEquals(10L, vo.getProductId());
        assertEquals(4, vo.getRating());
        assertEquals("不错", vo.getContent());
        assertNotNull(vo.getCreatedAt());
    }

    private static Review buildReview(Long id, Long productId, int rating, String content) {
        Review r = new Review();
        r.setId(id);
        r.setProductId(productId);
        r.setUserId(100L);
        r.setOrderId(200L);
        r.setRating(rating);
        r.setContent(content);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }
}
