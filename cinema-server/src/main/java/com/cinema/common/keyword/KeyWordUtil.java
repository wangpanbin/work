package com.cinema.common.keyword;

import java.util.*;

/**
 * DFA 关键词提取工具 — 基于 Trie 树的文本关键词匹配.
 * <p>从 jc-club 面试模块移植, 适配智能客服场景:
 * 从用户输入文本中提取预定义关键词, 用于知识库匹配.
 */
public class KeyWordUtil {

    @SuppressWarnings("rawtypes")
    private static final Map WORD_MAP = new HashMap(256);
    private static volatile boolean initialized = false;

    public static boolean isInit() {
        return initialized;
    }

    /** 从文本中提取所有匹配的关键词 */
    public static List<String> extract(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<String> wordList = new ArrayList<>();
        char[] charset = text.toCharArray();
        for (int i = 0; i < charset.length; i++) {
            FlagIndex fi = getFlagIndex(charset, i, 0);
            if (fi.isFlag()) {
                StringBuilder builder = new StringBuilder();
                for (int j : fi.getIndex()) {
                    builder.append(text.charAt(j));
                }
                wordList.add(builder.toString());
            }
        }
        // 去重
        return new ArrayList<>(new LinkedHashSet<>(wordList));
    }

    /** 加载关键词到 DFA 字典 */
    public static void loadWords(Collection<String> words) {
        if (words == null || words.isEmpty()) return;
        for (String word : words) {
            if (word == null || word.isEmpty()) continue;
            Map current = WORD_MAP;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                Object sub = current.get(c);
                if (sub != null) {
                    current = (Map) sub;
                } else {
                    Map newMap = new HashMap(4);
                    newMap.put("isEnd", String.valueOf(EndType.HAS_NEXT.ordinal()));
                    current.put(c, newMap);
                    current = newMap;
                }
                if (i == word.length() - 1) {
                    current.put("isEnd", String.valueOf(EndType.IS_END.ordinal()));
                }
            }
        }
        initialized = true;
    }

    /** 清空字典(重新加载前调用) */
    public static void clear() {
        WORD_MAP.clear();
        initialized = false;
    }

    @SuppressWarnings("rawtypes")
    private static FlagIndex getFlagIndex(char[] charset, int begin, int skip) {
        FlagIndex fi = new FlagIndex();
        Map current = WORD_MAP;
        boolean flag = false;
        int count = 0;
        List<Integer> index = new ArrayList<>();
        for (int i = begin; i < charset.length; i++) {
            char c = charset[i];
            Map sub = (Map) current.get(c);
            if (count > skip || (i == begin && sub == null)) break;
            if (sub != null) {
                current = sub;
                count = 0;
                index.add(i);
            } else {
                count++;
                if (flag && count > skip) break;
            }
            if ("1".equals(current.get("isEnd"))) flag = true;
        }
        fi.setFlag(flag);
        fi.setIndex(index);
        return fi;
    }
}
