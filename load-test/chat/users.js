import http from 'k6/http';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 부하 테스트 시드(zelo.seed.load-test.enabled=true, LoadTestUserSeedLoader)의 이메일 사용자로 로그인한다.
// 채팅은 id 5,001~10,000 사용자만 쓴다 (10,001~15,000은 견적 테스트). 시드 이메일은 load{id}@test.com, 비밀번호는 모두 Zelo1234!
const FIRST_USER_ID = 5001;
const LAST_USER_ID = 10000;
const PASSWORD = 'Zelo1234!';

export function loginUsers(baseUrl, count) {
    if (count > LAST_USER_ID - FIRST_USER_ID + 1) {
        throw new Error(`채팅 사용자는 ${FIRST_USER_ID}~${LAST_USER_ID}번까지만 쓸 수 있음 count=${count}`);
    }
    const tokens = [];
    for (let i = 0; i < count; i++) {
        tokens.push(login(baseUrl, `load${FIRST_USER_ID + i}@test.com`));
    }
    return tokens;
}

function login(baseUrl, email) {
    const res = http.post(`${baseUrl}/api/users/login`, JSON.stringify({ email, password: PASSWORD }),
        { headers: JSON_HEADERS });
    const token = res.status === 200 ? res.json('data.accessToken') : null;
    if (!token) {
        throw new Error(`로그인 실패 ${email} status=${res.status} body=${res.body}`);
    }
    return token;
}
