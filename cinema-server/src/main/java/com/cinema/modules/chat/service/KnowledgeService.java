package com.cinema.modules.chat.service;

import com.cinema.modules.chat.entity.Knowledge;
import com.cinema.modules.chat.mapper.KnowledgeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影院常见问答知识库服务(spec #20 ID-4).
 *
 * <p><b>初始化</b>:{@code @PostConstruct init()} 一次性从 DB 加载到内存 + 拆 keywordList,
 * 后续 searchFaq 是 O(n*m) 简单遍历(n ≤ 几十,m 平均 ≤ 5),&lt; 1ms。
 *
 * <p><b>匹配策略</b>:query.toLowerCase() 后,对每条 knowledge 的 keywordList 做 contains,
 * 统计命中数;score = 命中数 / keywordList.size() * 100 + sortOrder;命中数 = 0 跳过;
 * 按 score 降序,取 top-3 返回。
 *
 * <p><b>缓存失效</b>:FAQ 数据变更需重启生效(本 spec 不引入 @Scheduled 周期刷新;
 * 后续扩到几百条时可加)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeMapper knowledgeMapper;

    private List<Knowledge> knowledgeList = new ArrayList<>();

    /** 触发初始化(测试可手工调,生产由 Spring 启动时 @PostConstruct 触发)。 */
    public void init() {
        knowledgeList = knowledgeMapper.selectList(null);
        for (Knowledge k : knowledgeList) {
            if (k.getKeywords() != null && !k.getKeywords().isBlank()) {
                k.setKeywordList(Arrays.asList(k.getKeywords().split(",")));
            }
        }
        log.info("[chat-faq] 知识库加载完成:{} 条", knowledgeList.size());
    }

    /**
     * 搜索 FAQ。
     *
     * @param query 用户问题文本(null/空/空白 → 返空 List)
     * @return top-3 命中结果,每条 {question, answer, score};按 score 降序
     */
    public List<Map<String, String>> searchFaq(String query) {
        if (query == null || query.isBlank()) return List.of();

        String q = query.trim().toLowerCase();
        List<Map<String, String>> hits = new ArrayList<>();

        for (Knowledge k : knowledgeList) {
            List<String> kws = k.getKeywordList();
            if (kws == null || kws.isEmpty()) continue;

            int hitCount = 0;
            for (String kw : kws) {
                if (q.contains(kw.toLowerCase().trim())) {
                    hitCount++;
                }
            }
            if (hitCount == 0) continue;

            double score = (double) hitCount / kws.size() * 100
                    + (k.getSortOrder() != null ? k.getSortOrder() : 0);

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("question", k.getQuestion());
            entry.put("answer", k.getAnswer());
            entry.put("score", String.format("%.1f", score));
            hits.add(entry);
        }

        // 按 score 降序
        hits.sort((a, b) -> Double.compare(
                Double.parseDouble(b.get("score")),
                Double.parseDouble(a.get("score"))
        ));

        // 取 top-3
        return hits.size() > 3 ? hits.subList(0, 3) : hits;
    }
}