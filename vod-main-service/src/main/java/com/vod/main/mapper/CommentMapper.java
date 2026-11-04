package com.vod.main.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.vod.main.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
