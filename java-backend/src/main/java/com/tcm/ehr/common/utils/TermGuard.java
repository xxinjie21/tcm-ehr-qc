package com.tcm.ehr.common.utils;

/**
 * 术语语境护栏：判断一段术语原文是否处于「否证 / 已愈」语境。
 *
 * <p>为什么要独立成类：这条判据原先只写在 {@link EntityNormalizer}（解析链路），
 * 2026-10-18 实测发现清洗链路（{@code GovernanceServiceImpl.normalizeEntityList}）没有它 ——
 * 同一条 `无黄汗` 在解析链路被拦、在清洗链路被归成 `黄汗`（**语义反转**并写进导出件）。
 * 口径只写一处，两条链路共用。</p>
 *
 * <p>判据与 {@link EntityNormalizer} 原实现逐字相同（无/不/未/莫/非/缺/寡/忌/禁/欠 + 「已+痊愈」），
 * 已知代价也照旧：`不饥`/`不渴` 这类本身带否定的规范症状会被一起拦（它们在精确命中层不受影响）。</p>
 */
public final class TermGuard {

    private TermGuard() {
    }

    /** 该术语原文是否处于否证 / 已愈语境。 */
    public static boolean isNegatedContext(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            switch (raw.charAt(i)) {
                case '无': case '不': case '未': case '莫': case '非':
                case '缺': case '寡': case '忌': case '禁': case '欠':
                    return true;
                default:
                    break;
            }
        }
        int idx = raw.indexOf('已');
        if (idx >= 0 && idx + 1 < raw.length()) {
            String rest = raw.substring(idx + 1);
            for (String cured : new String[]{"愈", "好", "干净", "退", "消", "止", "解", "除", "去"}) {
                if (rest.startsWith(cured)) {
                    return true;
                }
            }
        }
        return false;
    }
}
