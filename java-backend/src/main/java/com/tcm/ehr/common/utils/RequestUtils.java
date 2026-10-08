package com.tcm.ehr.common.utils;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求上下文工具（鉴权与操作日志共用）：
 * JwtInterceptor 校验通过后把 currentUserId / currentUsername / currentRole /
 * currentOrgId / currentOrgRole 写入 request 属性，本类统一读取，
 * 替代各 Controller 各自复制的 operator() 方法。
 * 无请求上下文（如定时任务、单元测试）时统一返回 "unknown"，不抛异常。
 *
 * <p>§七 L1 已删除 {@code currentIp()}（不记操作 IP），随之删掉只为它服务的
 * {@code currentRequest()} 与两个 servlet import。</p>
 *
 * <p><b>组信息刻意不进 JWT</b>（阶段2）：移人 / 转让组长 / 停用组必须<b>立即</b>生效，
 * 放进有效期 24h 的 token 里意味着最长要等一天才生效。故 {@code JwtInterceptor}
 * 每请求查一次 {@code organization_members}（走 {@code INDEX(user_id,is_primary)}）。</p>
 *
 * <p>⚠️ <b>后台线程会拿到 "unknown" 而不是 null</b>：这些访问器读的是线程绑定的
 * {@code RequestContextHolder}。异步任务（批量重算 / 批量解析）必须在<b>提交线程</b>
 * 捕获所需值再传给 worker，见 {@code qc_task.role} 快照。</p>
 */
public final class RequestUtils {

    /** JwtInterceptor 写入的 request 属性名 */
    public static final String ATTR_USER_ID = "currentUserId";
    public static final String ATTR_USERNAME = "currentUsername";
    public static final String ATTR_ROLE = "currentRole";
    public static final String ATTR_ORG_ID = "currentOrgId";
    public static final String ATTR_ORG_ROLE = "currentOrgRole";
    /**
     * 「不限组织」标记（批次 6）：管理员且 {@code auth.admin-can-view-data=true} 时为 true。
     *
     * <p><b>为什么不能复用 orgId 为空来表示</b>：批次 4 之后 orgId 为空走 fail-closed
     * （查不到任何数据），拿它当「不限组织」会让开关一开就什么都看不到 ——
     * 与「看全部」正好相反，是个很难一眼看出的反向 bug。</p>
     */
    public static final String ATTR_VIEW_ALL_ORGS = "viewAllOrgs";

    /** 系统级角色：管理员（唯一有跨组与配置写权限的身份） */
    public static final String ROLE_ADMIN = "管理员";
    /** 系统级角色：普通用户（组织所有者 / 普通成员都是它；「待分配池」概念已废弃） */
    public static final String ROLE_USER = "用户";

    /** 组内角色：组长 */
    public static final String ORG_ROLE_OWNER = "owner";
    /** 组内角色：组员 */
    public static final String ORG_ROLE_MEMBER = "member";

    private static final String UNKNOWN = "unknown";

    private RequestUtils() {
    }

    /** 当前用户 ID */
    public static String currentUserId() {
        return attr(ATTR_USER_ID);
    }

    /** 当前操作人用户名 */
    public static String currentUsername() {
        return attr(ATTR_USERNAME);
    }

    /** 当前操作人系统级角色（管理员/用户） */
    public static String currentRole() {
        return attr(ATTR_ROLE);
    }

    /** 取当前系统角色；非请求线程/异常时装"unknown"，调用方按"不是管理员"处理（H4 捕获值的兜底） */
    public static String safeCurrentRole() {
        try {
            return attr(ATTR_ROLE);
        } catch (Exception e) {
            return "";
        }
    }

    /** 取当前组织角色（owner/member）；非请求线程/异常时给空串（H4 捕获值的兜底） */
    public static String safeCurrentOrgRole() {
        try {
            return attr(ATTR_ORG_ROLE);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 当前操作人所属课题组。
     *
     * <p><b>无组时返回空串而不是 null</b>：调用方（{@code RecordFilter}）会把它当
     * 「查不到数据」的判据，空串比 null 更不容易在下游被当成「未设置」而放过。</p>
     */
    public static String currentOrgId() {
        String v = attr(ATTR_ORG_ID);
        return UNKNOWN.equals(v) ? "" : v;
    }

    /** 当前操作人的组织内角色（owner/member）；无组织时为 {@code "unknown"} */
    public static String currentOrgRole() {
        return attr(ATTR_ORG_ROLE);
    }

    /** 本请求是否「不限组织」（管理员看全部）；false 时按 orgId 过滤，空 orgId 走 fail-closed */
    public static boolean viewAllOrgs() {
        return Boolean.TRUE.equals(rawAttr(ATTR_VIEW_ALL_ORGS));
    }

    /**
     * 取请求属性的<b>原始</b>值（不转字符串）。
     *
     * <p>⚠️ 不能用 {@link #attr}：它是给「角色 / 用户名」这类<b>文本</b>属性用的，
     * 会把值 {@code String.valueOf} 成字符串并把空值兜成 {@code "unknown"}。
     * 布尔标记那样取会得到 {@code "true"}/{@code "unknown"}，
     * 再拿 {@code Boolean.TRUE.equals(...)} 比就<b>恒为 false</b> ——
     * 于是 {@code auth.admin-can-view-data} 开关静默失效，管理员被永久限定在
     * 自己所属组织里、看不到其它组织的数据（实测 1000 条只看得到 500 条）。</p>
     *
     * @param key 属性名
     * @return 原始值；无请求上下文时返回 null
     */
    private static Object rawAttr(String key) {
        try {
            return RequestContextHolder.currentRequestAttributes()
                    .getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        } catch (Exception e) {
            // 无请求上下文（后台线程 / 单测）返回 null，由调用方按 false 处理
            return null;
        }
    }

    /** 当前操作人是否是系统管理员 */
    public static boolean isAdmin() {
        return ROLE_ADMIN.equals(currentRole());
    }

    /** 当前操作人是否是本组织所有者 */
    public static boolean isOrgOwner() {
        return ORG_ROLE_OWNER.equals(currentOrgRole());
    }

    /** 取请求属性（鉴权拦截器写入的角色等）；无请求上下文或取不到时返回 unknown */
    private static String attr(String key) {
        try {
            // 1. 从当前请求属性里取
            Object value = RequestContextHolder.currentRequestAttributes()
                    .getAttribute(key, RequestAttributes.SCOPE_REQUEST);
            if (value == null) {
                return UNKNOWN;
            }
            // 2. 空白与字面 "null" 都算取不到（属性可能被序列化成字符串 "null"）
            String text = String.valueOf(value);
            return text.isBlank() || "null".equals(text) ? UNKNOWN : text;
        } catch (Exception e) {
            // 3. 无请求上下文也返回 unknown，不抛给业务代码
            return UNKNOWN;
        }
    }
}
