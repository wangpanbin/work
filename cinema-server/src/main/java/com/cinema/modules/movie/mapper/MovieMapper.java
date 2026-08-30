package com.cinema.modules.movie.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.movie.entity.Movie;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MovieMapper extends BaseMapper<Movie> {
}
