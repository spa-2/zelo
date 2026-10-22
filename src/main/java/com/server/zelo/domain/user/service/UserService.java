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
        // 서비스를 직접 호출해도 대소문자만 다른 이메일이 중복 가입되지 않게 여기서도 정규화함
        String normalizedEmail = User.normalizeEmail(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 비밀번호는 평문으로 저장하지 않고 암호화해서 저장
        return userRepository.save(User.of(normalizedEmail, passwordEncoder.encode(password), name, phoneNumber));
    }

    public User createSocialUser(String email, String name, String phoneNumber) {
        String normalizedEmail = User.normalizeEmail(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        return userRepository.save(User.ofSocial(normalizedEmail, name, phoneNumber));
    }

    // 이메일과 패스워드가 일치하는지 확인
    public User getUserByEmailAndPassword(String email, String password) {
        User user = userRepository.findByEmail(User.normalizeEmail(email))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // 소셜 로그인을 소유한 사람이 로그인을 시도했다면 패스워드가 null이고 소셜 로그인은 일반 로그인을 하지 못함
        if (user.isPasswordNull()){
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return user;
    }

    //아이디가 일치하는지 확인
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }
}
