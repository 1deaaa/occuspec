package com.occuspec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occuspec.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {}
