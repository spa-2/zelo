import { abortVisit, authHeaders, completeVisit, get, loginUser, pickUser, startVisit, think } from '../common.js';
import { chat, quoteChatMessages } from './chat.js';

// B. 견적·질문 (계획서 9-2 B). 사용자 5,001~15,000 (채팅 시드의 방 기록이 있는 사용자, 계획서 3-5)
// 로그인 → 방 목록 → 메시지 5개 → 메시지 목록. 요청 8개
const FIRST_USER_ID = 5001;
const USER_COUNT = 10000;

export function quoteChat() {
    startVisit();
    const token = loginUser(pickUser(FIRST_USER_ID, USER_COUNT));
    if (!token) {
        abortVisit('login');
        return;
    }
    think();

    const result = chat(token, quoteChatMessages());
    if (result.failedStep) {
        abortVisit(result.failedStep);
        return;
    }
    think();

    const messages = get(`/api/chat/rooms/${result.roomId}/messages`, { name: 'getMessages', api: 'read' },
        { headers: authHeaders(token) });
    if (!messages.ok) {
        abortVisit('getMessages');
        return;
    }
    completeVisit();
}
