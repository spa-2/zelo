package com.server.zelo.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.server.zelo.common.exception.BusinessException;
import com.server.zelo.common.exception.ErrorCode;
import com.server.zelo.domain.product.entity.Category;
import com.server.zelo.domain.user.entity.User;
import com.server.zelo.domain.user.entity.UserBlockedCategory;
import com.server.zelo.domain.user.repository.UserBlockedCategoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserBlockedCategoryServiceTest {

    @Mock
    private UserBlockedCategoryRepository userBlockedCategoryRepository;

    @InjectMocks
    private UserBlockedCategoryService userBlockedCategoryService;

    private final User user = user(1L);
    private final Category nuts = category(7L, "견과류");

    @Test
    void 카테고리를_막는다() {
        // given
        given(userBlockedCategoryRepository.existsByUser_IdAndCategory_Id(1L, 7L)).willReturn(false);
        given(userBlockedCategoryRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        // when
        UserBlockedCategory blocked = userBlockedCategoryService.blockCategory(user, nuts);

        // then
        assertThat(blocked.getUser()).isEqualTo(user);
        assertThat(blocked.getCategory()).isEqualTo(nuts);
    }

    @Test
    void 이미_막은_카테고리면_예외가_발생한다() {
        // given
        given(userBlockedCategoryRepository.existsByUser_IdAndCategory_Id(1L, 7L)).willReturn(true);

        // when, then
        assertThatThrownBy(() -> userBlockedCategoryService.blockCategory(user, nuts))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_BLOCKED_CATEGORY);
        then(userBlockedCategoryRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 동시에_막아서_유니크_제약에_걸려도_이미_막은_것으로_알려준다() {
        // given
        given(userBlockedCategoryRepository.existsByUser_IdAndCategory_Id(1L, 7L)).willReturn(false);
        given(userBlockedCategoryRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk"));

        // when, then
        assertThatThrownBy(() -> userBlockedCategoryService.blockCategory(user, nuts))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_BLOCKED_CATEGORY);
    }

    @Test
    void 막은_카테고리_ID만_모은다() {
        // given
        given(userBlockedCategoryRepository.findAllByUser_IdOrderByIdAsc(1L)).willReturn(List.of(
                UserBlockedCategory.of(user, nuts), UserBlockedCategory.of(user, category(8L, "갑각류"))));

        // when, then
        assertThat(userBlockedCategoryService.findBlockedCategoryIds(1L)).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void 막은_카테고리를_푼다() {
        // when
        userBlockedCategoryService.unblockCategory(1L, 7L);

        // then
        then(userBlockedCategoryRepository).should().deleteByUser_IdAndCategory_Id(1L, 7L);
    }

    private User user(Long id) {
        User user = User.of("user@zelo.com", "encoded", "사용자", "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Category category(Long id, String name) {
        Category category = Category.of(name, true);
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }
}