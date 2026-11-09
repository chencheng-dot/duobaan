package org.example.duobaan.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.example.duobaan.config.DuobaanProperties;
import org.example.duobaan.model.ApiProfile;
import org.example.duobaan.model.ApiProfileType;
import org.example.duobaan.model.dto.ApiProfileDTO;
import org.example.duobaan.repository.ApiProfileRepository;
import org.example.duobaan.repository.SystemConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ApiProfileService#listAiProfiles()} 单元测试（Mockito，不启动 Spring、不连库）。
 *
 * <p>该方法是设置页「AI 模型」统一列表与聊天页模型下拉的数据源，必须保证：
 * <ol>
 *   <li>只查 5 种 AI 能力，天气模型不会被混进来；</li>
 *   <li>返回的 Key 一律打码，明文绝不出服务层；</li>
 *   <li>仓储顺序原样保留（前端按类型分组展示，重排会打乱分组）。</li>
 * </ol>
 */
@DisplayName("ApiProfileService.listAiProfiles —— AI 模型统一列表")
class ApiProfileServiceListAiTest {

    private final ApiProfileRepository repo = mock(ApiProfileRepository.class);
    private final ApiProfileService service = new ApiProfileService(
            repo,
            mock(SystemConfigRepository.class),
            mock(DuobaanProperties.class),
            mock(ObjectMapper.class));

    @Test
    @DisplayName("只查 5 种 AI 类型，天气模型不参与")
    void queriesExactlyFiveAiTypes() {
        when(repo.findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(anyCollection()))
                .thenReturn(List.of());

        service.listAiProfiles();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ApiProfileType>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(repo).findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(captor.capture());
        verifyNoMoreInteractions(repo);

        Collection<ApiProfileType> types = captor.getValue();
        assertAll(
                () -> assertEquals(5, types.size(), "应恰好查询 5 种 AI 能力"),
                () -> assertTrue(types.contains(ApiProfileType.LLM)),
                () -> assertTrue(types.contains(ApiProfileType.IMAGE)),
                () -> assertTrue(types.contains(ApiProfileType.AUDIO)),
                () -> assertTrue(types.contains(ApiProfileType.VIDEO)),
                () -> assertTrue(types.contains(ApiProfileType.EMBEDDING)),
                () -> assertFalse(types.contains(ApiProfileType.WEATHER), "天气模型是独立一块，不能混进 AI 列表"));
    }

    @Test
    @DisplayName("返回的 DTO 只带打码 Key，明文不出服务层")
    void masksApiKeyInResult() {
        ApiProfile p = profile(ApiProfileType.LLM, "DeepSeek", "sk-1234567890abcdef");
        when(repo.findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(anyCollection()))
                .thenReturn(List.of(p));

        List<ApiProfileDTO> result = service.listAiProfiles();

        assertEquals(1, result.size());
        ApiProfileDTO dto = result.get(0);
        assertAll(
                () -> assertEquals("sk-1****cdef", dto.apiKeyMasked()),
                () -> assertNotEquals("sk-1234567890abcdef", dto.apiKeyMasked()),
                () -> assertEquals("DeepSeek", dto.name()),
                () -> assertEquals(ApiProfileType.LLM, dto.profileType()));
    }

    @Test
    @DisplayName("保持仓储返回顺序，不重排（前端按类型分组渲染）")
    void preservesRepositoryOrder() {
        ApiProfile llm = profile(ApiProfileType.LLM, "文本-A", "sk-aaaaaaaaaaaa");
        ApiProfile image = profile(ApiProfileType.IMAGE, "图片-B", "sk-bbbbbbbbbbbb");
        ApiProfile video = profile(ApiProfileType.VIDEO, "视频-C", "sk-cccccccccccc");
        when(repo.findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(anyCollection()))
                .thenReturn(List.of(llm, image, video));

        List<ApiProfileDTO> result = service.listAiProfiles();

        assertAll(
                () -> assertEquals(List.of("文本-A", "图片-B", "视频-C"),
                        result.stream().map(ApiProfileDTO::name).toList()),
                () -> assertEquals(ApiProfileType.VIDEO, result.get(2).profileType()));
    }

    @Test
    @DisplayName("一条都没有时返回空列表（不是 null，前端可直接遍历）")
    void returnsEmptyListWhenNoProfiles() {
        when(repo.findByProfileTypeInOrderByProfileTypeAscUpdatedAtDesc(anyCollection()))
                .thenReturn(List.of());

        List<ApiProfileDTO> result = service.listAiProfiles();

        assertTrue(result.isEmpty());
    }

    // ======================= 测试数据构造 =======================

    private static ApiProfile profile(ApiProfileType type, String name, String apiKey) {
        ApiProfile p = new ApiProfile();
        p.setProfileType(type);
        p.setName(name);
        p.setProvider("deepseek");
        p.setBaseUrl("https://api.deepseek.com/v1");
        p.setModel("deepseek-chat");
        p.setApiKey(apiKey);
        p.setTimeoutSeconds(30);
        p.setIsActive(true);
        p.setCreatedAt(LocalDateTime.of(2026, 11, 9, 10, 0));
        p.setUpdatedAt(LocalDateTime.of(2026, 11, 9, 10, 0));
        return p;
    }
}
