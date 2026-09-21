package com.cinema.common.keyword;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DFA 关键词匹配 — 标记索引
 */
@Data
public class FlagIndex {
    private boolean flag;
    private List<Integer> index = new ArrayList<>();
}
