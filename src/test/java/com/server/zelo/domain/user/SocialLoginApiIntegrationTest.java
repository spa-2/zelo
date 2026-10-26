package com.server.zelo.domain.user;

import com.jayway.jsonpath.JsonPath;
import com.server.zelo.common.util.HashUtil;
import com.server.zelo.domain.user.entity.SocialAccount;
import com.server.zelo.domain.user.entity.SocialProvider;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.repository.SocialAccountRepository;
import com.server.zelo.domain.user.repository.UserRepository;
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

// 소셜 로그인 → 가입 → 재로그인 → 재발급이 FakeSocialLoginClient(code로 결과가 정해짐)를 통해 실제로 이어지는지 검증한다
// DB는 테스트가 끝나면 롤백되지만 Redis 키는 남아서 직접 지운다
@Transactional
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SocialLoginApiIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SocialAccountRepository socialAccountRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private HashUtil hashUtil;

    private final List<String> redisKeysToClean = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(redisKeysToClean);
    }

    // ===== 처음 온 사용자 =====

    @Test
    void 처음_온_사용자는_가입이_필요하다는_응답과_가입용_정보를_받는다() throws Exception {
        // given
        String code = uniqueCode();

        // when
        ResultActions result = login("google", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(true))
                .andExpect(jsonPath("$.data.signupToken").isNotEmpty())
                .andExpect(jsonPath("$.data.email").value(code + "@fake.example.com"))
                .andExpect(jsonPath("$.data.name").value(code))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        // then
        // 가입을 끝내기 전에는 로그인된 것이 아니라서 쿠키도 없고 사용자도 만들어지지 않음
        assertThat(result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(userRepository.findByEmail(code + "@fake.example.com")).isEmpty();
    }

    @Test
    void 이름이_없는_구글_계정은_가입_화면에_이메일_앞부분을_이름으로_보여_준다() throws Exception {
        // when & then
        login("google", "no-name")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(true))
                .andExpect(jsonPath("$.data.name").value("no-name"));
    }

    @Test
    void 이름이_50자를_넘는_구글_계정은_50자로_보정해서_보여_주고_가입된다() throws Exception {
        // given
        String body = login("google", "long-name")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("가".repeat(50)))
                .andReturn().getResponse().getContentAsString();
        String ticket = JsonPath.read(body, "$.data.signupToken");

        // when
        signup(ticket).andExpect(status().isCreated());

        // then
        User user = userRepository.findByEmail("long-name@fake.example.com").orElseThrow();
        assertThat(user.getName()).isEqualTo("가".repeat(50));
        redisKeysToClean.add("refresh:" + user.getId());
    }

    // ===== 가입 후 로그인 =====

    @Test
    void 가입한_뒤_같은_code로_다시_로그인하면_기존_회원으로_로그인된다() throws Exception {
        // given
        String code = uniqueCode();
        signUpWith(code);

        // when
        ResultActions result = login("google", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.signupToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        // then
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refreshToken=", "HttpOnly", "Secure", "Path=/api/auth/reissue");
    }

    @Test
    void 로그인으로_받은_리프레시_쿠키로_재발급할_수_있다() throws Exception {
        // given
        String code = uniqueCode();
        signUpWith(code);
        String refreshToken = refreshTokenFrom(login("google", code));
        redisKeysToClean.add("refresh:rotated:" + hashUtil.hash(refreshToken));

        // when & then
        // 로그인 때 토큰을 Redis에 저장하지 않으면 여기서 401이 남
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    // 소셜 로그인도 일반 로그인처럼 사용자당 세션 하나만 유지하고 이전 기기는 다른 기기 로그인으로 안내해야 함
    @Test
    void 다시_로그인하면_이전_리프레시_쿠키는_다른_기기_로그인으로_거부된다() throws Exception {
        // given
        String code = uniqueCode();
        String oldRefreshToken = signUpWith(code);
        login("google", code).andExpect(status().isOk());

        // when & then
        mockMvc.perform(post("/api/auth/reissue").cookie(new Cookie("refreshToken", oldRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_005"));
    }

    // 신원은 제공자 ID이고 이메일 정책은 신규 가입자에게만 적용되므로, 이미 가입한 사용자는 이메일이 없어도 로그인되어야 함
    @Test
    void 이미_가입한_사용자는_구글이_이메일을_주지_않아도_로그인된다() throws Exception {
        // given
        // Fake의 no-email 코드는 providerId가 "no-email"이고 이메일이 없는 프로필을 돌려줌
        User user = userRepository.save(User.ofSocial(uniqueCode() + "@fake.example.com", "기존", "010-1234-5678"));
        socialAccountRepository.save(SocialAccount.of(user, SocialProvider.GOOGLE, "no-email"));
        redisKeysToClean.add("refresh:" + user.getId());

        // when & then
        login("google", "no-email")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(false))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    // ===== 거부되어야 하는 경우 =====

    @Test
    void 이메일이_없는_구글_계정은_가입할_수_없다() throws Exception {
        login("google", "no-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_010"))
                .andExpect(jsonPath("$.data.signupToken").doesNotExist());
    }

    @Test
    void 이메일이_인증되지_않은_구글_계정은_가입할_수_없다() throws Exception {
        login("google", "unverified")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_010"));
    }

    @Test
    void 같은_이메일로_일반_가입한_사용자가_있으면_409이고_번호표를_발급하지_않는다() throws Exception {
        // given
        String code = uniqueCode();
        userRepository.save(User.of(code + "@fake.example.com", "encodedPassword", "기존", "010-9999-8888"));

        // when & then
        login("google", code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_011"))
                .andExpect(jsonPath("$.data.signupToken").doesNotExist());
    }

    @Test
    void 제공자가_code를_거절하면_401이다() throws Exception {
        login("google", "rejected")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_009"));
    }

    @Test
    void 알_수_없는_code도_401이다() throws Exception {
        login("google", "not-a-fake-code")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_009"));
    }

    @Test
    void 제공자에_장애가_있으면_503이다() throws Exception {
        login("google", "provider-down")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_013"));
    }

    @Test
    void 지원하지_않는_제공자는_400이다() throws Exception {
        login("facebook", uniqueCode())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_012"));
    }

    @Test
    void 제공자_이름은_대소문자를_구분하지_않는다() throws Exception {
        login("GOOGLE", uniqueCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupRequired").value(true));
    }

    @Test
    void code가_비어있으면_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/social/google/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"\", \"redirectUri\": \"" + REDIRECT_URI + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    // ===== 도우미 =====

    private ResultActions login(String provider, String code) throws Exception {
        ResultActions result = mockMvc.perform(post("/api/auth/social/{provider}/login", provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"" + code + "\", \"redirectUri\": \"" + REDIRECT_URI + "\"}"));

        // 가입까지 진행하지 않는 테스트가 발급받은 번호표는 Redis에 남아서 함께 지움
        String body = result.andReturn().getResponse().getContentAsString();
        if (body.contains("\"signupToken\"")) {
            String ticket = JsonPath.read(body, "$.data.signupToken");
            redisKeysToClean.add("social:signup:" + hashUtil.hash(ticket));
        }
        return result;
    }

    private ResultActions signup(String ticket) throws Exception {
        return mockMvc.perform(post("/api/auth/social/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signupToken": "%s", "phoneNumber": "010-1234-5678",
                         "place": "집", "addresses": "서울 강남구", "zipCode": "12345"}
                        """.formatted(ticket)));
    }

    // 로그인 → 번호표 → 가입까지 진행하고, 가입 직후 받은 리프레시 토큰을 돌려줌
    private String signUpWith(String code) throws Exception {
        String body = login("google", code).andReturn().getResponse().getContentAsString();
        String ticket = JsonPath.read(body, "$.data.signupToken");
        ResultActions signup = signup(ticket).andExpect(status().isCreated());

        User user = userRepository.findByEmail(code + "@fake.example.com").orElseThrow();
        String refreshToken = refreshTokenFrom(signup);
        redisKeysToClean.add("refresh:" + user.getId());
        // 이후 다시 로그인하면 이 토큰이 밀려나면서 superseded 표식이 남아서 함께 지움
        redisKeysToClean.add("refresh:superseded:" + hashUtil.hash(refreshToken));
        return refreshToken;
    }

    private String refreshTokenFrom(ResultActions result) {
        String setCookie = result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return setCookie.split(";")[0].substring("refreshToken=".length());
    }

    // 로컬 DB에 쌓인 데이터와 겹치지 않도록 매번 새로운 code를 쓴다 (이메일은 최대 50자)
    private String uniqueCode() {
        return "fake-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
