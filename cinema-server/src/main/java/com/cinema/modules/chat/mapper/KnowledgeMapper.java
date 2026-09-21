package com.cinema.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.chat.entity.Knowledge;
import org.apache.ibatis.annotations.Mapper;

/**
 * 影院常见问答知识库 Mapper(spec #20 ID-3).
 *
 * <p>暂不引入自定义 SQL — KnowledgeService 用 {@code BaseMapper.selectList(null)}
 * 全量加载到内存,18 条数据规模下 contains 匹配 < 1ms。后续扩到几百条再考虑分页/索引。
 */
@Mapper
public interface KnowledgeMapper extends BaseMapper<Knowledge> {
}