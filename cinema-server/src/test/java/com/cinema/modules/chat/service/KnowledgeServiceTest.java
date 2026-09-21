package com.cinema.modules.chat.service;

import com.cinema.modules.chat.entity.Knowledge;
import com.cinema.modules.chat.mapper.KnowledgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * spec #20 / TD-1 — KnowledgeService 7 case 单测.
 *
 * <p>覆盖 contains 匹配 + 多 keyword 命中数排序 + sort_order 加权 +
 * 大小写不敏感 + 空 query / 不命中 / top-3 截断七条关键不变量。
 *
 * <p>风格参考 {@link ChatToolsTest}:@ExtendWith(MockitoExtension) + @Mock
 * KnowledgeMapper + 手工 new KnowledgeService(knowledgeMapper),{@code init()}
 * 在每个 case 的 stub 里手工触发(避免 @PostConstruct 依赖 Spring 上下文)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeServiceTest {

    @Mock private KnowledgeMapper knowledgeMapper;
    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(knowledgeMapper);
    }

    /** stub mapper + 触发 @PostConstruct init() 重新加载. */
    private void stubKnowledge(Knowledge... entries) {
        when(knowledgeMapper.selectList(null)).thenReturn(Arrays.asList(entries));
        service.init();
    }

    @Test
    @DisplayName("单 keyword 命中 → 返回该 knowledge 的 {question, answer}")
    void singleKeywordHit_returnsMatch() {
        stubKnowledge(knowledge(1L, "买票,购票,下单", "怎么买票", "购票流程A", 10));
        List<Map<String, String>> result = service.searchFaq("我想买票");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("question")).isEqualTo("怎么买票");
        assertThat(result.get(0).get("answer")).isEqualTo("购票流程A");
    }

    @Test
    @DisplayName("多 keyword 命中,命中数最高的排前面")
    void multiKeywordHit_highestRankFirst() {
        stubKnowledge(
                knowledge(1L, "买票,购票,选座", "怎么买票", "购票流程A", 5),
                knowledge(2L, "买票,价格,票价", "票价多少", "票价流程B", 5)
        );
        // query 含 "买票" + "价格":
        //   知识1 命中 1/3("买票") = 33.3
        //   知识2 命中 2/3("买票","价格") = 66.6
        List<Map<String, String>> result = service.searchFaq("我想买票,价格多少");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("question")).isEqualTo("票价多少");
        assertThat(result.get(1).get("question")).isEqualTo("怎么买票");
    }

    @Test
    @DisplayName("sort_order 加权生效 (sort_order=20 优先于 sort_order=10)")
    void sortOrderHigherPriority() {
        stubKnowledge(
                knowledge(1L, "买票", "怎么买票", "流程A", 10),
                knowledge(2L, "买票", "打招呼", "你好!", 20)
        );
        // 两条都命中 1/1,score = 100+10 vs 100+20 → 知识2 排前
        List<Map<String, String>> result = service.searchFaq("买票");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("question")).isEqualTo("打招呼");
        assertThat(result.get(1).get("question")).isEqualTo("怎么买票");
    }

    @Test
    @DisplayName("大小写不敏感 (query.toLowerCase() 后 contains)")
    void caseInsensitive() {
        stubKnowledge(knowledge(1L, "BuyTicket", "How to buy", "购票流程", 10));
        List<Map<String, String>> result = service.searchFaq("buyticket");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("answer")).isEqualTo("购票流程");
    }

    @Test
    @DisplayName("空 query → 空 List (null / \"\" / 空白都视为空)")
    void emptyQuery_emptyResult() {
        stubKnowledge(knowledge(1L, "买票", "怎么买票", "流程", 10));
        assertThat(service.searchFaq("")).isEmpty();
        assertThat(service.searchFaq(null)).isEmpty();
        assertThat(service.searchFaq("   ")).isEmpty();
    }

    @Test
    @DisplayName("完全不命中 → 空 List")
    void noMatch_emptyResult() {
        stubKnowledge(knowledge(1L, "买票,购票", "怎么买票", "流程", 10));
        assertThat(service.searchFaq("今天天气如何")).isEmpty();
    }

    @Test
    @DisplayName("命中多条 → 返回 top-3 (不返回全部)")
    void returnTop3() {
        Knowledge[] all = new Knowledge[5];
        for (int i = 0; i < 5; i++) {
            all[i] = knowledge((long) i, "买票", "怎么买票" + i, "流程" + i, 10);
        }
        stubKnowledge(all);
        List<Map<String, String>> result = service.searchFaq("买票");
        assertThat(result).hasSize(3);
    }

    // ============ helpers ============

    private static Knowledge knowledge(Long id, String keywords, String question, String answer, Integer sortOrder) {
        Knowledge k = new Knowledge();
        k.setId(id);
        k.setKeywords(keywords);
        k.setQuestion(question);
        k.setAnswer(answer);
        k.setSortOrder(sortOrder);
        k.setKeywordList(Arrays.asList(keywords.split(",")));
        return k;
    }
}