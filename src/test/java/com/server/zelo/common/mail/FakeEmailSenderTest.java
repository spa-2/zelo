package com.server.zelo.common.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FakeEmailSenderTest {

    private final FakeEmailSender fakeEmailSender = new FakeEmailSender();
    private final Logger logger = (Logger) LoggerFactory.getLogger(FakeEmailSender.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void 실제로_발송하지_않고_수신자와_본문을_로그로_남긴다() {
        // when
        fakeEmailSender.send("jieun@example.com", "제목", "코드는 123456");

        // then 프론트와 통합 테스트에서 코드를 확인할 수 있어야 함
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("jieun@example.com", "제목", "123456");
    }

    @Test
    void 발송_중_예외가_발생하지_않는다() {
        // when & then
        assertThatCode(() -> fakeEmailSender.send("jieun@example.com", "제목", "본문"))
                .doesNotThrowAnyException();
    }
}
