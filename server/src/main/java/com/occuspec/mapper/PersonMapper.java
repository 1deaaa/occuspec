package com.occuspec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occuspec.entity.Person;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PersonMapper extends BaseMapper<Person> {}
