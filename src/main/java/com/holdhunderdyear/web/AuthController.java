package com.holdhunderdyear.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.holdhunderdyear.common.BizException;
import com.holdhunderdyear.common.ErrorCode;
import com.holdhunderdyear.common.JwtUtil;
import com.holdhunderdyear.common.R;
import com.holdhunderdyear.game.model.dto.LoginDTO;
import com.holdhunderdyear.game.model.dto.RegisterDTO;
import com.holdhunderdyear.game.model.enums.GameMode;
import com.holdhunderdyear.market.entity.SysUser;
import com.holdhunderdyear.market.mapper.SysUserMapper;
import com.holdhunderdyear.world.WorldSeeder;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：注册（播种世界）、登录（签发 JWT）、当前用户信息。
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final WorldSeeder worldSeeder;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(WorldSeeder worldSeeder,
                          SysUserMapper sysUserMapper,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.worldSeeder = worldSeeder;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/auth/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterDTO dto) {
        long userId;
        try {
            userId = worldSeeder.seedUser(dto.getUsername(), passwordEncoder.encode(dto.getPassword()),
                    GameMode.of(dto.getMode()));
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        return R.ok(Map.of("userId", userId));
    }

    @PostMapping("/auth/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginDTO dto) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        return R.ok(Map.of(
                "token", jwtUtil.generate(user.getId()),
                "userId", user.getId(),
                "username", user.getUsername()));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(@AuthenticationPrincipal Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return R.ok(Map.of("userId", user.getId(), "username", user.getUsername()));
    }
}
