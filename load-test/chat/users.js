import http from 'k6/http';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 부하 테스트 시드(zelo.seed.load-test.enabled=true, LoadTestUserSeedLoader)의 사용자로 로그인한다.
// 채팅은 id 5,001~10,000 사용자만 쓴다 (10,001~15,000은 견적 테스트). 비밀번호는 모두 Zelo1234!
// 시드는 id 끝자리 1~7을 이메일 사용자(load{id}@test.com), 8·9·0을 소셜 사용자(code fake-load-{id})로 만든다.
// 채팅 번호대를 차례로 쓰면 이메일 70%, 소셜 30%로 로그인한다 (계획서 3-5)
const FIRST_USER_ID = 5001;
const LAST_USER_ID = 10000;
const PASSWORD = 'Zelo1234!';
const EMAIL_LAST_DIGIT_MAX = 7;

export function isEmailUser(userId) {
    const lastDigit = userId % 10;
    return lastDigit >= 1 && lastDigit <= EMAIL_LAST_DIGIT_MAX;
}

// 사용자 id에 맞는 로그인 API를 부른다. 두 API 모두 토큰을 data.accessToken으로 돌려준다
export function login(baseUrl, userId, params) {
    if (isEmailUser(userId)) {
        return http.post(`${baseUrl}/api/users/login`,
            JSON.stringify({ email: `load${userId}@test.com`, password: PASSWORD }), params);
    }
    return http.post(`${baseUrl}/api/auth/social/google/login`,
        JSON.stringify({ code: `fake-load-${userId}`, redirectUri: 'http://localhost' }), params);
}

// 소셜 계정이 없으면 200이어도 토큰 대신 가입 티켓이 오므로, 토큰이 있는지로 성공을 본다
export function accessTokenOf(res) {
    return res.status === 200 ? res.json('data.accessToken') || null : null;
}

export function loginUsers(baseUrl, count) {
    if (count > LAST_USER_ID - FIRST_USER_ID + 1) {
        throw new Error(`채팅 사용자는 ${FIRST_USER_ID}~${LAST_USER_ID}번까지만 쓸 수 있음 count=${count}`);
    }
    const tokens = [];
    for (let i = 0; i < count; i++) {
        const userId = FIRST_USER_ID + i;
        const res = login(baseUrl, userId, { headers: JSON_HEADERS });
        const token = accessTokenOf(res);
        if (!token) {
            throw new Error(`로그인 실패 userId=${userId} status=${res.status} body=${res.body}`);
        }
        tokens.push(token);
    }
    return tokens;
}
