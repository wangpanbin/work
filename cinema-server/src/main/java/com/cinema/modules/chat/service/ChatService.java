package com.cinema.modules.chat.service;

import com.cinema.common.keyword.KeyWordUtil;
import com.cinema.modules.chat.entity.Knowledge;
import com.cinema.modules.chat.mapper.KnowledgeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 智能客服匹配服务
 * <p>流程: 用户输入 → DFA 关键词提取 → 知识库评分 → 返回最佳回答
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final KnowledgeMapper knowledgeMapper;

    private List<Knowledge> knowledgeList = new ArrayList<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 从 DB 加载知识库并重建 DFA 字典 */
    public void reload() {
        knowledgeList = knowledgeMapper.selectList(null);
        Set<String> allKeywords = new LinkedHashSet<>();
        for (Knowledge k : knowledgeList) {
            List<String> kws = Arrays.asList(k.getKeywords().split(","));
            k.setKeywordList(kws);
            allKeywords.addAll(kws);
        }
        KeyWordUtil.clear();
        KeyWordUtil.loadWords(allKeywords);
        log.info("[智能客服] 知识库加载完成: {} 条知识, {} 个关键词", knowledgeList.size(), allKeywords.size());
    }

    /** 匹配用户输入, 返回最佳回答 */
    public String match(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return "请输入您想咨询的问题~";
        }
        if (!KeyWordUtil.isInit()) {
            reload();
        }

        // 1. DFA 提取关键词
        List<String> matched = KeyWordUtil.extract(userInput.toLowerCase());
        log.info("[智能客服] 用户输入='{}' 提取关键词={}", userInput, matched);

        if (matched.isEmpty()) {
            return "抱歉，我不太理解您的问题。您可以试试问我：怎么买票、怎么退票、票价多少、怎么取票等问题~";
        }

        // 2. 评分: 每条知识看命中了几个关键词
        Knowledge best = null;
        double bestScore = 0;

        for (Knowledge k : knowledgeList) {
            List<String> kws = k.getKeywordList();
            if (kws == null || kws.isEmpty()) continue;
            int hitCount = 0;
            for (String kw : kws) {
                if (matched.contains(kw)) hitCount++;
            }
            if (hitCount == 0) continue;
            // 评分 = 命中率 * 权重加成(排序高的优先)
            double score = (double) hitCount / kws.size() * 100
                    + (k.getSortOrder() != null ? k.getSortOrder() : 0);
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }

        if (best == null) {
            return "抱歉，我不太理解您的问题。您可以试试问我：怎么买票、怎么退票、票价多少、怎么取票等问题~";
        }

        return best.getAnswer();
    }
}
