package com.cinema.modules.chat.agent;

import com.cinema.modules.chat.vo.ActionCardVO;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词 ↔ 解析器契约的守卫。
 *
 * <p><b>它守的是什么</b>: 2026-10-25 实测发现, 提示词里写着
 * 「出 {@code ORDER_PROPOSAL} 卡<b>之前必须先调 {@code findContiguousSeats}</b> 拿座位索引,
 * 拿不到就不出卡」, 而解析器({@code ReplyCardParser})/服务端({@code OrderProposalService})/
 * 前端({@code buildRepickRequest})<b>全都把 seatIndexes 当成可选</b>(ADR-0004 决策 D8)。
 *
 * <p>后果: 模型要先有座位才能出卡 → 要先定场次 → 一晚有多个场次时它就反问
 * 「你打算看哪一场」→ <b>永远走不到下单卡</b>, 浏览器回归 S22 长期停在 SKIP。
 *
 * <p>这类「声明与实现不符」靠人 review 是抓不住的 —— 提示词是自然语言, 解析器是代码,
 * 两边永远不会同时出现在评审者的视野里。所以在这里把它变成一条会红的断言。
 */
class CinemaAssistantPromptTest {

    /** 提示词里可能出现的卡型名。正则硬编码是有意的: 将来加第 4 种卡型而忘了改提示词, 这里会红。 */
    private static final Pattern CARD_TYPE = Pattern.compile("\\b(SEAT_SUGGESTION|ORDER_PROPOSAL|ORDER_GUIDE)\\b");

    private static String prompt() {
        Method m;
        try {
            m = CinemaAssistant.class.getMethod("chat", String.class, String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("CinemaAssistant#chat 的签名变了, 这条测试要跟着改", e);
        }
        SystemMessage ann = m.getAnnotation(SystemMessage.class);
        assertThat(ann).as("CinemaAssistant#chat 必须带 @SystemMessage").isNotNull();
        return String.join("\n", ann.value());
    }

    @Test
    @DisplayName("提示词里的卡型集合 == ActionCardVO 的卡型常量(加卡型忘改提示词就红)")
    void prompt_card_types_match_action_card_vo() {
        Set<String> inPrompt = new LinkedHashSet<>();
        Matcher mm = CARD_TYPE.matcher(prompt());
        while (mm.find()) {
            inPrompt.add(mm.group(1));
        }
        assertThat(inPrompt).containsExactlyInAnyOrder(
                ActionCardVO.TYPE_SEAT_SUGGESTION,
                ActionCardVO.TYPE_ORDER_PROPOSAL,
                ActionCardVO.TYPE_ORDER_GUIDE);
    }

    @Test
    @DisplayName("提示词必须声明 seatIndexes 可选 —— 不能要求模型先查座位才出卡")
    void prompt_declares_seat_indexes_optional() {
        String p = prompt();
        boolean declaredOptional = Arrays.stream(p.split("\n"))
                .anyMatch(line -> line.contains("seatIndexes") && line.contains("可选"));
        assertThat(declaredOptional)
                .as("提示词必须明确写明 seatIndexes 可选(座位最终由服务端重读实时位图决定)")
                .isTrue();
        assertThat(p)
                .as("不能再要求「出卡前必须先调 findContiguousSeats」—— 那会让模型在多个候选场次时反问, 出卡链路死锁")
                .doesNotContain("必须先调");
    }

    @Test
    @DisplayName("提示词必须保留「已表达下单意图时不要反问」与「张数缺省 2 张」")
    void prompt_keeps_no_clarification_and_default_count() {
        String p = prompt();
        assertThat(p)
                .as("助手在用户已表达下单意图时不得反问「看哪一场」「坐几个人」")
                .contains("不要反问");
        assertThat(p)
                .as("用户没说张数时按 2 张出卡(提示词必须写明这个缺省)")
                .contains("默认给 2 张");
    }
}
