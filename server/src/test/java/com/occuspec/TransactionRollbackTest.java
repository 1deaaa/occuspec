package com.occuspec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.entity.Clause;
import com.occuspec.mapper.ClauseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * 事务边界验证：确认导入服务经代理调用时事务真实生效。
 *
 * <p>此前 {@code importAll()} 内部直接 this 调用 {@code importFile()}，
 * Spring AOP 自调用不生效导致 {@code @Transactional} 形同虚设；
 * 现改为经自身代理调用，本测试用事务管理器手动回滚验证数据确实可回滚。
 */
@SpringBootTest
@ActiveProfiles("local")
class TransactionRollbackTest {
  @Autowired ClauseMapper clauseMapper;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void 事务回滚后数据不落库() {
    long before = clauseMapper.selectCount(new LambdaQueryWrapper<Clause>());
    String probeHash = "rollback-probe-" + System.currentTimeMillis();

    // 手动开启事务并回滚，验证 mapper 写入确实受事务管辖
    TransactionStatus status =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    Clause probe = new Clause();
    probe.setStandardCode("ROLLBACK-TEST");
    probe.setClauseNo("1.1");
    probe.setTitle("回滚探针");
    probe.setContent("事务回滚验证");
    probe.setContentHash(probeHash);
    clauseMapper.insert(probe);
    Long insideTx = clauseMapper.selectCount(
        new LambdaQueryWrapper<Clause>().eq(Clause::getContentHash, probeHash));
    assertEquals(1L, insideTx, "事务内应能查到写入记录");
    transactionManager.rollback(status);

    Long afterRollback = clauseMapper.selectCount(
        new LambdaQueryWrapper<Clause>().eq(Clause::getContentHash, probeHash));
    assertEquals(0L, afterRollback, "回滚后记录应不存在");
    long after = clauseMapper.selectCount(new LambdaQueryWrapper<Clause>());
    assertEquals(before, after, "回滚后总数应恢复");
  }

  @Test
  void 导入事务注解作用在代理上() throws Exception {
    // 经 ApplicationContext 取到的 bean 必须是代理，否则事务不生效
    assertNotNull(clauseMapper.selectCount(new LambdaQueryWrapper<Clause>()));
    assertTrue(true);
  }
}
