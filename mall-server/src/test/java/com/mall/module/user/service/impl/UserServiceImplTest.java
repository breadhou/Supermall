package com.mall.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.enums.ResultStatus;
import com.mall.common.exception.BusinessException;
import com.mall.common.utils.SnowflakeIdUtil;
import com.mall.infra.redis.RedisService;
import com.mall.common.enums.UserStatus;
import com.mall.module.user.entity.dto.LoginDTO;
import com.mall.module.user.entity.dto.RegisterDTO;
import com.mall.module.user.entity.po.User;
import com.mall.module.user.mapper.UserMapper;
import com.mall.security.utils.JwtUtil;
import com.mall.security.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserServiceImpl 单元测试。
 *
 * 测试策略：
 * - Mapper / PasswordEncoder / RedisService 用 Mockito @Mock 模拟
 * - JwtUtil / SnowflakeIdUtil 是静态工具方法，用 MockedStatic 拦截
 * - 每个测试方法独立，不依赖 Spring 容器启动
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RedisService redisService;

    @InjectMocks
    private UserServiceImpl userService;

    // 静态方法 Mock 需要在 try-with-resources 里使用，这里声明为字段方便 @AfterEach 关闭
    private MockedStatic<JwtUtil> jwtUtilMock;
    private MockedStatic<SnowflakeIdUtil> snowflakeIdUtilMock;
    private MockedStatic<UserContext> userContextMock;

    private RegisterDTO validRegisterDto;
    private LoginDTO validLoginDto;
    private User mockUser;

    @BeforeEach
    void setUp() {
        // 每个测试前准备好静态 Mock 和通用 DTO
        jwtUtilMock = mockStatic(JwtUtil.class);
        snowflakeIdUtilMock = mockStatic(SnowflakeIdUtil.class);
        userContextMock = mockStatic(UserContext.class);

        validRegisterDto = new RegisterDTO();
        validRegisterDto.setUsername("testuser");
        validRegisterDto.setPassword("123456");
        validRegisterDto.setPhone("13800138000");

        validLoginDto = new LoginDTO();
        validLoginDto.setUsername("testuser");
        validLoginDto.setPassword("123456");

        // 登录测试用的模拟用户对象
        mockUser = new User()
                .setId(1000001L)
                .setUsername("testuser")
                .setPassword("$2a$10$encryptedPassword")
                .setStatus(UserStatus.NORMAL);
    }

    @AfterEach
    void tearDown() {
        // 必须关闭静态 Mock，否则会影响其他测试
        if (jwtUtilMock != null) jwtUtilMock.close();
        if (snowflakeIdUtilMock != null) snowflakeIdUtilMock.close();
        if (userContextMock != null) userContextMock.close();
    }

    // ==================== 注册成功 ====================

    @Test
    void register_shouldReturnLoginVO_whenSuccess() {
        // 准备：用户名和手机号都不重复
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        // 密码加密（BCrypt 输出是固定模拟值）
        when(passwordEncoder.encode("123456")).thenReturn("$2a$10$encryptedPassword");
        // 雪花 ID
        long fakeUserId = 1000001L;
        snowflakeIdUtilMock.when(SnowflakeIdUtil::nextId).thenReturn(fakeUserId);
        // JWT
        when(JwtUtil.generateAccessToken(fakeUserId)).thenReturn("mock-access-token");
        when(JwtUtil.generateRefreshToken(fakeUserId)).thenReturn("mock-refresh-token");

        // 执行
        var result = userService.register(validRegisterDto);

        // 验证：返回的 LoginVO 各字段正确
        assertNotNull(result);
        assertEquals("mock-access-token", result.getAccessToken());
        assertEquals("mock-refresh-token", result.getRefreshToken());
        assertEquals(fakeUserId, result.getId());
        assertEquals("testuser", result.getUsername());

        // 验证：确实调用了 insert 写入数据库
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("testuser", savedUser.getUsername());
        assertEquals("$2a$10$encryptedPassword", savedUser.getPassword());
        assertEquals("13800138000", savedUser.getPhone());
        assertEquals(fakeUserId, savedUser.getId());
    }

    // ==================== 用户名重复 ====================

    @Test
    void register_shouldThrowException_whenUsernameExists() {
        // 准备：第一次 selectCount 查用户名，返回 >0 表示已存在
        when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        // 执行 + 验证
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(validRegisterDto));

        assertEquals(ResultStatus.DATA_ALREADY_EXIST, ex.getStatus());

        // 验证：因为抛异常了，insert 和 JWT 生成都不应该被调用
        verify(userMapper, never()).insert(any(User.class));
    }

    // ==================== 手机号重复 ====================

    @Test
    void register_shouldThrowException_whenPhoneExists() {
        // 准备：第一次 selectCount（查用户名）返回 0，第二次（查手机号）返回 1
        when(userMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(0L)   // 用户名不重复
                .thenReturn(1L);  // 手机号重复

        // 执行 + 验证
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register(validRegisterDto));

        assertEquals(ResultStatus.MOBILE_ERROR, ex.getStatus());
        verify(userMapper, never()).insert(any(User.class));
    }

    // ==================== 登录：用户名不存在 ====================

    @Test
    void login_shouldThrowException_whenUserNotFound() {
        // 准备：selectOne 返回 null，表示用户名不存在
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 执行 + 验证
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(validLoginDto));

        assertEquals(ResultStatus.USER_NOT_EXIST, ex.getStatus());

        // 验证：用户不存在时，不应该调用密码比对和 JWT 生成
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // ==================== 登录：密码错误 ====================

    @Test
    void login_shouldThrowException_whenPasswordWrong() {
        // 准备：查到了用户，但密文比对失败
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockUser);
        when(passwordEncoder.matches("123456", mockUser.getPassword())).thenReturn(false);

        // 执行 + 验证
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(validLoginDto));

        assertEquals(ResultStatus.PASSWORD_ERROR, ex.getStatus());

        // 验证：密码错了，不应该生成 JWT
        verify(userMapper).selectOne(any(LambdaQueryWrapper.class));
    }

    // ==================== 登录：用户被禁用 ====================

    @Test
    void login_shouldThrowException_whenUserDisabled() {
        // 准备：用户存在、密码正确，但 status = DISABLED
        User disabledUser = new User()
                .setId(1000002L)
                .setUsername("bannedUser")
                .setPassword("$2a$10$hashedPassword")
                .setStatus(UserStatus.DISABLED);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("bannedUser");
        dto.setPassword("correctPassword");

        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(disabledUser);
        when(passwordEncoder.matches("correctPassword", disabledUser.getPassword())).thenReturn(true);

        // 执行 + 验证
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(dto));

        assertEquals(ResultStatus.USER_BANNED, ex.getStatus());
    }

    // ==================== 登录成功 ====================

    @Test
    void login_shouldReturnLoginVO_whenSuccess() {
        // 准备：用户存在、密码正确、状态正常
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockUser);
        when(passwordEncoder.matches("123456", mockUser.getPassword())).thenReturn(true);
        when(JwtUtil.generateAccessToken(mockUser.getId())).thenReturn("mock-access-token");
        when(JwtUtil.generateRefreshToken(mockUser.getId())).thenReturn("mock-refresh-token");

        // 执行
        var result = userService.login(validLoginDto);

        // 验证：返回的 LoginVO 各字段正确
        assertNotNull(result);
        assertEquals("mock-access-token", result.getAccessToken());
        assertEquals("mock-refresh-token", result.getRefreshToken());
        assertEquals(mockUser.getId(), result.getId());
        assertEquals(mockUser.getUsername(), result.getUsername());
    }

    // ==================== 刷新 Token ====================

    @Test
    void refresh_shouldReturnNewAccessToken() {
        long userId = 1000001L;
        // 准备：当前登录用户（由 JwtAuthFilter 写入 UserContext）
        userContextMock.when(UserContext::getUserId).thenReturn(userId);
        when(JwtUtil.generateAccessToken(userId)).thenReturn("new-mock-access-token");

        // 执行
        var result = userService.refresh();

        // 验证：只签发新的 accessToken，refreshToken 和 username 不返回
        assertNotNull(result);
        assertEquals("new-mock-access-token", result.getAccessToken());
        assertEquals(userId, result.getId());
        assertNull(result.getRefreshToken());  // 不含 refreshToken
        assertNull(result.getUsername());      // 不含 username
    }
}
