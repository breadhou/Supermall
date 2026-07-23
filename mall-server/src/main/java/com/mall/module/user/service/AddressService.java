package com.mall.module.user.service;

import com.mall.module.user.entity.dto.AddressDTO;
import com.mall.module.user.entity.vo.AddressVO;

import java.util.List;

public interface AddressService {

    /** 查询当前登录用户的所有收货地址 */
    List<AddressVO> listAddresses();

    /** 新增收货地址，返回创建后的地址 */
    AddressVO addAddress(AddressDTO dto);

    /** 修改收货地址，id 标识要修改哪条 */
    AddressVO updateAddress(Long id, AddressDTO dto);

    /** 删除收货地址 */
    void deleteAddress(Long id);
}
