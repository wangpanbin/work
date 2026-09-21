package com.cinema.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 影院常见问答知识库实体(spec #20 ID-2).
 *
 * <p>移植自 PR #15 {@code Knowledge.java},字段对齐 {@code sql/06_qa_knowledge.sql}.
 *
 * <p>{@code keywordList} 非持久化字段:service 层在 init 时一次性 split 缓存,
 * searchFaq 时按 List 遍历 contains。
 */
@Data
@TableName("qa_knowledge")
public class Knowledge {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 匹配关键词,逗号分隔。 */
    private String keywords;

    /** 示例问题。 */
    private String question;

    /** 回答内容。 */
    private String answer;

    /** 排序权重(越大越优先)。 */
    private Integer sortOrder;

    private LocalDateTime createdAt;

    /** 非持久化:service 层拆分后的关键词列表。 */
    @TableField(exist = false)
    private List<String> keywordList;
}