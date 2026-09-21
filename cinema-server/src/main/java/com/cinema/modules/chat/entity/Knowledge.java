package com.cinema.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能客服知识库
 */
@Data
@TableName("qa_knowledge")
public class Knowledge {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 匹配关键词, 逗号分隔 */
    private String keywords;

    /** 示例问题 */
    private String question;

    /** 回答内容 */
    private String answer;

    /** 排序(越大越优先) */
    private Integer sortOrder;

    private LocalDateTime createdAt;

    /** 非持久化: 拆分后的关键词列表 */
    @TableField(exist = false)
    private List<String> keywordList;
}
