package com.server.zelo.domain.user.service;

import com.server.zelo.common.bean.PasswordEncoder;
import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public User createUser(String email, String password, String name, String phoneNumber) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 비밀번호는 평문으로 저장하지 않고 암호화해서 저장
        return userRepository.save(User.of(email, passwordEncoder.encode(password), name, phoneNumber));
    }
}
