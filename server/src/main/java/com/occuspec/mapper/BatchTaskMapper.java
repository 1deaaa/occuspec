package com.occuspec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occuspec.entity.BatchTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BatchTaskMapper extends BaseMapper<BatchTask> {
  /**
   * 原子抢占任务：仅当状态为 PENDING 时置为 RUNNING。
   * 返回影响行数，1 表示抢占成功，0 表示已被其他节点抢走。
   *
   * @param id 任务主键
   * @param pending 期望的当前状态
   * @param running 抢占后的状态
   */
  @Update("UPDATE batch_tasks SET status = #{running}, updated_at = NOW(3)"
      + " WHERE id = #{id} AND status = #{pending}")
  int claim(@Param("id") long id, @Param("pending") String pending, @Param("running") String running);
}
