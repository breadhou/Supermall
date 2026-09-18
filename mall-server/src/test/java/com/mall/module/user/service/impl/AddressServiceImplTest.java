package com.mall.module.user.service.impl;

import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.infra.redis.RedisService;
import com.mall.module.user.entity.dto.AddressDTO;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.entity.vo.AddressVO;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.security.utils.UserContext;
import com.mall.common.utils.SnowflakeIdUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for address management.
 *
 * <p>{@code isDefault} is an {@code Integer} on the DTO, the PO and the VO.
 * It used to be {@code Boolean} on the PO only, which made
 * {@code BeanUtils.copyProperties} skip the field silently and left every
 * address non-default.  These tests pin the flag down on both write paths.</p>
 */
@ExtendWith(MockitoExtension.class)
class AddressServiceImplTest {

    private static final Long USER_ID = 1001L;

    @Mock
    private AddressMapper addressMapper;
    @Mock
    private RedisService redisService;

    @InjectMocks
    private AddressServiceImpl addressService;

    /**
     * 雪花 ID 现在是 fail-closed 的：未注入实例身份就不发号。
     * 本类不关心具体取值，只需要「已配置」这个前提；同一组值重复配置是幂等的。
     */
    @BeforeAll
    static void configureSnowflakeIds() {
        SnowflakeIdUtil.configure(0L, 0L);
    }

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- default flag propagation -----------------------------------------

    @Test
    void addAddress_shouldApplyDefaultFlagFromRequest() {
        AddressVO vo = addressService.addAddress(addressDto(1));

        assertEquals(Integer.valueOf(1), vo.getIsDefault());
    }

    @Test
    void addAddress_shouldPersistDefaultFlagOnTheEntity() {
        addressService.addAddress(addressDto(1));

        ArgumentCaptor<Address> inserted = ArgumentCaptor.forClass(Address.class);
        verify(addressMapper).insert(inserted.capture());
        assertEquals(Integer.valueOf(1), inserted.getValue().getIsDefault());
    }

    @Test
    void updateAddress_shouldApplyDefaultFlagFromRequest() {
        when(addressMapper.selectOne(any())).thenReturn(existingAddress(7L, 0));

        AddressVO vo = addressService.updateAddress(7L, addressDto(1));

        assertEquals(Integer.valueOf(1), vo.getIsDefault());
    }

    // ---- default address exclusivity --------------------------------------

    @Test
    void addAddress_shouldClearPreviousDefaultWhenPromotingToDefault() {
        when(addressMapper.selectList(any())).thenReturn(List.of(existingAddress(1L, 1)));

        addressService.addAddress(addressDto(1));

        ArgumentCaptor<Address> updated = ArgumentCaptor.forClass(Address.class);
        verify(addressMapper).updateById(updated.capture());
        assertEquals(Integer.valueOf(0), updated.getValue().getIsDefault());
    }

    @Test
    void addAddress_shouldLeaveExistingDefaultUntouchedWhenRequestIsNotDefault() {
        addressService.addAddress(addressDto(0));

        verify(addressMapper, never()).updateById(any(Address.class));
    }

    // ---- ownership --------------------------------------------------------

    @Test
    void updateAddress_shouldRejectAddressThatDoesNotBelongToCurrentUser() {
        // selectOne is scoped by user_id, so null means "not found or not owned".
        when(addressMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> addressService.updateAddress(999L, addressDto(0)));

        assertEquals(ResultStatus.ADDRESS_NOT_EXIST, exception.getStatus());
    }

    private Address existingAddress(Long id, Integer isDefault) {
        return new Address()
                .setId(id)
                .setUserId(USER_ID)
                .setReceiver("原收件人")
                .setPhone("13900000000")
                .setProvince("广东省")
                .setCity("深圳市")
                .setDistrict("南山区")
                .setDetail("原地址")
                .setIsDefault(isDefault);
    }

    private AddressDTO addressDto(Integer isDefault) {
        AddressDTO dto = new AddressDTO();
        dto.setReceiver("测试收件人");
        dto.setPhone("13900000000");
        dto.setProvince("广东省");
        dto.setCity("深圳市");
        dto.setDistrict("南山区");
        dto.setDetail("测试路1号");
        dto.setIsDefault(isDefault);
        return dto;
    }
}
