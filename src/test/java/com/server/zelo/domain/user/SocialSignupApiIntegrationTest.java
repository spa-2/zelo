package com.server.zelo.domain.user;

import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserAddress;
import com.server.zelo.domain.user.repository.SocialAccountRepository;
import com.server.zelo.domain.user.repository.UserAddressRepository;
import com.server.zelo.domain.user.repository.UserRepository;
import com.server.zelo.domain.user.service.SocialSignupTicketService;
import com.server.zelo.domain.user.social.SocialProfile;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 번호표(Redis) → 락(Redisson) → 가입(DB) → 토큰(Redis)이 실제로 이어지는지 한 번에 검증한다
// DB는 테스트가 끝나면 롤백되지만 Redis 키는 남아서 직접 지운다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SocialSignupApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SocialSignupTicketService socialSignupTicketService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private HashUtil hashUtil;

    private final List<String> redisKeysToClean = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(redisKeysToClean);
    }

    @Test
    void 소셜_가입하면_사용자_소셜_계정_기본_배송지가_저장되고_로그인된다() throws Exception {
        // given
        String email = uniqueEmail();
        String providerId = UUID.randomUUID().toString();
        String ticket = socialSignupTicketService.issue(profile(email, providerId));

        // when
        ResultActions result = signup(ticket);

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        User user = userRepository.findByEmail(email).orElseThrow();
        // 소셜 가입자는 비밀번호가 없음 (users.password가 NOT NULL이면 저장에서 실패함)
        assertThat(user.isPasswordNull()).isTrue();
        assertThat(user.getName()).isEqualTo("지은");
        assertThat(user.getPhoneNumber()).isEqualTo("010-1234-5678");

        SocialAccount socialAccount = socialAccountRepository.findAll().stream()
                .filter(account -> account.getProviderId().equals(providerId))
                .findFirst().orElseThrow();
        assertThat(socialAccount.getUser().getId()).isEqualTo(user.getId());
        assertThat(socialAccount.getProvider()).isEqualTo(SocialProvider.GOOGLE);

        UserAddress address = userAddressRepository.findDefaultByUserId(user.getId()).orElseThrow();
        assertThat(address.getPlace()).isEqualTo("집");
        assertThat(address.getRecipientName()).isEqualTo("지은");

        // 가입 직후 받은 리프레시 토큰이 서버에 등록되어 있어야 함
        redisKeysToClean.add("refresh:" + user.getId());
        assertThat(stringRedisTemplate.hasKey("refresh:" + user.getId())).isTrue();
    }

    @Test
    void 가입_직후_받은_리프레시_쿠키로_재발급할_수_있다() throws Exception {
        // given
        String email = uniqueEmail();
        String ticket = socialSignupTicketService.issue(profile(email, UUID.randomUUID().toString()));
        ResultActions signup = signup(ticket);
        String refreshToken = refreshTokenFrom(signup);
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        redisKeysToClean.add("refresh:" + userId);
        redisKeysToClean.add("refresh:rotated:" + hashUtil.hash(refreshToken));

        // when & then
        // 가입 때 토큰을 Redis에 저장하지 않으면 여기서 401이 남
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void 가입한_리프레시_쿠키는_HttpOnly_Secure다() throws Exception {
        // given
        String email = uniqueEmail();
        String ticket = socialSignupTicketService.issue(profile(email, UUID.randomUUID().toString()));

        // when
        ResultActions result = signup(ticket);

        // then
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refreshToken=", "HttpOnly", "Secure", "Path=/api/auth/reissue");
        redisKeysToClean.add("refresh:" + userRepository.findByEmail(email).orElseThrow().getId());
    }

    @Test
    void 같은_번호표로_두_번_가입할_수_없다() throws Exception {
        // given
        String email = uniqueEmail();
        String ticket = socialSignupTicketService.issue(profile(email, UUID.randomUUID().toString()));
        signup(ticket).andExpect(status().isCreated());
        redisKeysToClean.add("refresh:" + userRepository.findByEmail(email).orElseThrow().getId());

        // when & then
        signup(ticket)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    @Test
    void 발급하지_않은_번호표로는_가입할_수_없다() throws Exception {
        // when & then
        signup("forged-ticket-" + UUID.randomUUID())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_008"));
    }

    @Test
    void 이미_일반_가입된_이메일이면_409이고_소셜_계정을_만들지_않는다() throws Exception {
        // given
        String email = uniqueEmail();
        String providerId = UUID.randomUUID().toString();
        userRepository.save(User.of(email, "encodedPassword", "기존", "010-9999-8888"));
        String ticket = socialSignupTicketService.issue(profile(email, providerId));

        // when & then
        signup(ticket)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_002"));
        assertThat(socialAccountRepository.findAll())
                .noneMatch(account -> account.getProviderId().equals(providerId));
    }

    @Test
    void 소셜_가입자는_비밀번호로_로그인할_수_없다() throws Exception {
        // given
        String email = uniqueEmail();
        String ticket = socialSignupTicketService.issue(profile(email, UUID.randomUUID().toString()));
        signup(ticket).andExpect(status().isCreated());
        redisKeysToClean.add("refresh:" + userRepository.findByEmail(email).orElseThrow().getId());

        // when & then
        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"Password1!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MEMBER_003"));
    }

    private ResultActions signup(String ticket) throws Exception {
        return mockMvc.perform(post("/api/auth/social/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signupToken": "%s", "phoneNumber": "010-1234-5678",
                         "place": "집", "addresses": "서울 강남구", "zipCode": "12345"}
                        """.formatted(ticket)));
    }

    private String refreshTokenFrom(ResultActions signup) {
        String setCookie = signup.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return setCookie.split(";")[0].substring("refreshToken=".length());
    }

    private SocialProfile profile(String email, String providerId) {
        return SocialProfile.of(SocialProvider.GOOGLE, providerId, email, true, "지은");
    }

    // 로컬 DB에 쌓인 데이터와 겹치지 않도록 매번 새로운 이메일을 쓴다 (이메일은 최대 50자)
    private String uniqueEmail() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
