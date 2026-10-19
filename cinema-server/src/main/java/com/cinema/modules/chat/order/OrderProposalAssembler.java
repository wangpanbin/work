package com.cinema.modules.chat.order;

import com.cinema.common.exception.BizException;
import com.cinema.modules.chat.vo.ActionCardVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 提案装配器 —— 把模型出的 {@code ORDER_PROPOSAL} 卡变成「服务端核过并可执行」的卡(ADR-0004)。
 *
 * <p><b>它为什么必须待在 LLM 之外</b>:这一步是整个「一键下单」的安全闸门。
 * 模型给的座位只是<b>提示</b>, 本组件会重读实时位图逐个复核; 模型的报价<b>直接丢弃</b>,
 * 价格一律取 session 表。只有通过复核的提案才会拿到 {@code proposalId},
 * 而 {@code proposalId} 才是确认端点唯一认的东西。
 *
 * <p><b>它不改 {@code ChatTools}</b>:助手不注册任何写工具(ADR-0002 仍然字面成立),
 * {@code ChatToolsStructureTest} 的四条断言一条都不用改。
 *
 * <p><b>容错</b>:模型不配合(没出卡 / 出错卡型 / 场次不存在)都不该让整轮对话失败 ——
 * 出错就丢那张卡,并在回复末尾追加一句说明。
 */
@Slf4j
@Component
public class OrderProposalAssembler {

    private final OrderProposalService proposalService;
    private final boolean enabled;

    public OrderProposalAssembler(OrderProposalService proposalService,
                                  @Value("${cinema.chat.order-enabled:true}") boolean enabled) {
        this.proposalService = proposalService;
        this.enabled = enabled;
    }

    /**
     * 装配结果: 处理后的卡片列表 + 需要追加到回复末尾的说明(可为 null)。
     */
    public record AssembleResult(List<ActionCardVO> cards, String note) {
    }

    /**
     * 处理一批卡片里的 {@code ORDER_PROPOSAL} 卡。
     *
     * @param userId 当前登录用户(null = 匿名); 身份只从服务端登录态来,模型给不了
     * @param cards  解析器产出的卡片列表
     */
    public AssembleResult assemble(Long userId, List<ActionCardVO> cards) {
        if (cards == null || cards.isEmpty()) {
            return new AssembleResult(List.of(), null);
        }
        List<ActionCardVO> result = new ArrayList<>(cards);
        Set<String> notes = new LinkedHashSet<>();

        for (int i = 0; i < result.size(); i++) {
            ActionCardVO card = result.get(i);
            if (!ActionCardVO.TYPE_ORDER_PROPOSAL.equals(card.getType())) {
                continue;   // 既有卡型(SEAT_SUGGESTION 等)原样透传
            }
            if (!enabled) {
                log.info("[chat-order] 下单功能未启用(order-enabled=false), 丢弃确认卡");
                result.remove(i--);
                notes.add("下单功能当前未启用");
                continue;
            }
            if (userId == null) {
                result.remove(i--);
                notes.add("请先登录,我再帮你下单");
                continue;
            }
            Long sessionId = parseSessionId(card.getSessionId());
            if (sessionId == null) {
                result.remove(i--);
                notes.add("没能识别出你要的场次,请再说一次");
                continue;
            }
            try {
                OrderProposal proposal = proposalService.createProposal(
                        userId, sessionId, card.getCount(), card.getSeatIndexes());
                result.set(i, applyServerValues(card, proposal));
            } catch (BizException e) {
                result.remove(i--);
                notes.add(e.getMessage() == null ? "暂时无法生成下单方案" : e.getMessage());
            } catch (Exception e) {
                log.error("[chat-order] 装配提案失败 session={}", sessionId, e);
                result.remove(i--);
                notes.add("下单方案生成失败,请稍后重试");
            }
        }
        return new AssembleResult(result, notes.isEmpty() ? null : String.join(";", notes));
    }

    /**
     * 用服务端的值覆写卡片 —— 模型说的价格、座位、影片信息在这里全部作废。
     *
     * <p>模型给的 {@code actionLabel} 也一并清掉: 按钮文案由前端 i18n 负责,
     * 避免后端硬编码中文导致英文界面下出现中文按钮。
     */
    private ActionCardVO applyServerValues(ActionCardVO card, OrderProposal p) {
        card.setSessionId(String.valueOf(p.getSessionId()));
        card.setSeatIndexes(p.getSeatIndexes());
        card.setSeatDesc(p.getSeatDesc());
        card.setPrice(p.getUnitPrice());
        card.setTotalAmount(p.getTotalAmount());
        card.setMovieTitle(p.getMovieTitle());
        card.setHallName(p.getHallName());
        card.setStartTime(p.getStartTime());
        card.setProposalId(p.getProposalId());
        card.setExpiresAt(p.getExpiresAt());
        card.setPendingCancelOrderNo(p.getPendingCancelOrderNo());
        card.setPendingCancelExpireAt(p.getPendingCancelExpireAt());
        card.setCount(p.getSeatIndexes() == null ? null : p.getSeatIndexes().size());
        card.setCardId(p.getProposalId());
        card.setActionLabel(null);
        return card;
    }

    /** 模型给的 sessionId 是字符串(雪花 ID 可能超 2^53),解析失败当作无效场次。 */
    private Long parseSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
