package com.mall.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.RedisService;
import com.mall.module.user.entity.dto.AddressDTO;
import com.mall.module.user.entity.po.Address;
import com.mall.module.user.entity.vo.AddressVO;
import com.mall.module.user.mapper.AddressMapper;
import com.mall.module.user.service.AddressService;
import com.mall.security.utils.UserContext;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private RedisService redisService;

    @Override
    public List<AddressVO> listAddresses() {
        Long userId = UserContext.getUserId();

        List<Address> addresses = addressMapper.selectList(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .orderByDesc(Address::getIsDefault)  // 默认地址排前面
                        .orderByDesc(Address::getId)
        );

        return addresses.stream().map(addr -> {
            AddressVO vo = new AddressVO();
            BeanUtils.copyProperties(addr, vo);
            return vo;
        }).toList();
    }

    @Override
    public AddressVO addAddress(AddressDTO dto) {
        Long userId = UserContext.getUserId();

        // 如果设为默认地址，先把旧默认地址取消
        if (dto.getIsDefault() != null && dto.getIsDefault() == 1) {
            clearDefaultAddress(userId);
        }

        Address addr = new Address();
        BeanUtils.copyProperties(dto, addr);
        addr.setId(SnowflakeIdUtil.nextId());
        addr.setUserId(userId);
        addressMapper.insert(addr);

        AddressVO vo = new AddressVO();
        BeanUtils.copyProperties(addr, vo);
        return vo;
    }

    @Override
    public AddressVO updateAddress(Long id, AddressDTO dto) {
        Long userId = UserContext.getUserId();

        // 查出来，确认是自己的地址
        Address addr = addressMapper.selectOne(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getId, id)
                        .eq(Address::getUserId, userId)
        );
        if (addr == null) {
            return null;
        }

        // 如果设为默认，先取消旧默认
        if (dto.getIsDefault() != null && dto.getIsDefault() == 1) {
            clearDefaultAddress(userId);
        }

        BeanUtils.copyProperties(dto, addr, "id", "userId");  // 不覆盖 id 和 userId
        addressMapper.updateById(addr);

        AddressVO vo = new AddressVO();
        BeanUtils.copyProperties(addr, vo);
        return vo;
    }

    @Override
    public void deleteAddress(Long id) {
        Long userId = UserContext.getUserId();

        addressMapper.delete(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getId, id)
                        .eq(Address::getUserId, userId)
        );
    }

    /** 取消该用户的所有默认地址 */
    private void clearDefaultAddress(Long userId) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId);
        wrapper.eq(Address::getIsDefault, 1);
        List<Address> defaults = addressMapper.selectList(wrapper);
        for (Address a : defaults) {
            a.setIsDefault(false);
            addressMapper.updateById(a);
        }
    }
}
