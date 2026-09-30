package com.tcm.ehr.common.config;

import com.tcm.ehr.domain.po.QcRule;
import com.tcm.ehr.mapper.QcRuleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 质控规则「每组织一份」的契约测试（批次 8c）。
 *
 * <p>锁两件事：①A 保存的规则不污染 B；②无自有规则的组织回退内置默认。</p>
 */
class QcRuleOrgTest {

    private Map<String, QcRule> rows;
    private QcRuleStore store;

    @BeforeEach
    void setUp() {
        rows = new ConcurrentHashMap<>();
        QcRuleMapper mapper = Mockito.mock(QcRuleMapper.class);
        Mockito.when(mapper.selectById(ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    // 显式 Object：String.valueOf 有 char[] 等重载，泛型推断会挑错
                    Object id = inv.getArgument(0);
                    return rows.get(String.valueOf(id));
                });
        Mockito.when(mapper.insert(ArgumentMatchers.any(QcRule.class))).thenAnswer(inv -> {
            QcRule r = inv.getArgument(0);
            rows.put(r.getOrgId(), r);
            return 1;
        });
        Mockito.when(mapper.updateById(ArgumentMatchers.any(QcRule.class))).thenAnswer(inv -> {
            QcRule r = inv.getArgument(0);
            rows.put(r.getOrgId(), r);
            return 1;
        });
        Mockito.when(mapper.deleteById(ArgumentMatchers.anyString())).thenAnswer(inv -> {
            // 显式 Object：String.valueOf 有 char[] 等重载，泛型推断会挑错
            Object id = inv.getArgument(0);
            rows.remove(String.valueOf(id));
            return 1;
        });
        store = new QcRuleStore(new ObjectMapper(), mapper);
    }

    @Test
    void orgWithoutOwnRulesFallsBackToDefaults() {
        QcRuleSet d = store.getFor("org-none");
        assertNotNull(d, "无自有规则时必须回退内置默认，而不是返回 null");
        assertEquals(QcRuleSet.defaults().getThresholds().getQualified(),
                d.getThresholds().getQualified(), "回退应与内置默认一致");
    }

    @Test
    void savedRulesAreScopedToThatOrg() {
        store.updateFor("org-a", QcRuleSet.defaults());

        assertNotNull(store.getFor("org-a"), "A 自己的规则应能读回");
        assertEquals(QcRuleSet.defaults().getThresholds().getQualified(),
                store.getFor("org-b").getThresholds().getQualified(),
                "B 没配过，回退默认而不是继承 A");
        // B 无行 => 不会把 A 的内容带过去
        assertTrue(!rows.containsKey("org-b"), "读取不应为无行的组织落库");
    }

    @Test
    void resetRemovesOnlyThatOrgRow() {
        store.updateFor("org-a", QcRuleSet.defaults());
        store.updateFor("org-b", QcRuleSet.defaults());

        store.resetFor("org-a");

        assertTrue(!rows.containsKey("org-a"), "重置应删掉该组织那一行");
        assertTrue(rows.containsKey("org-b"), "重置不应影响别的组织");
    }

    @Test
    void saveWithoutOrgContextIsRejected() {
        // 没有组织上下文就落库 = 写到「空组织」那行，之后谁都读得到
        assertThrows(IllegalArgumentException.class,
                () -> store.updateFor("", QcRuleSet.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> store.updateFor(null, QcRuleSet.defaults()));
    }
}
