// 더미 사용자 id 규칙 (LoadTestUserSeedLoader 참고)
// id 끝자리 1~7은 이메일 사용자(load{id}@test.com), 8·9·0은 소셜 사용자(code는 fake-load-{id})다.
// 스크립트는 "몇 번째 사용자"(1부터)로 사용자를 고르므로, 번호를 실제 id로 바꿔서 쓴다.

const SOCIAL_LAST_DIGITS = [8, 9, 10];

// n번째 이메일 사용자의 id: 1,2,...,7,11,12,...,17,21,...
export function emailUserId(n) {
    return Math.floor((n - 1) / 7) * 10 + ((n - 1) % 7) + 1;
}

// n번째 소셜 사용자의 id: 8,9,10,18,19,20,28,...
export function socialUserId(n) {
    return Math.floor((n - 1) / 3) * 10 + SOCIAL_LAST_DIGITS[(n - 1) % 3];
}
