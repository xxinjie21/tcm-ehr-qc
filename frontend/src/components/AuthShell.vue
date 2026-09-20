<template>
  <div class="auth-page">
    <div class="auth-shell">
      <aside class="auth-brand">
        <div class="ornament"></div>
        <h2>中医电子病历<br>质控与标准化系统</h2>
        <div class="en">TCM EHR Quality Control<br>&amp; Standardization</div>
        <div class="ver">v1.0 · 2026-09</div>
      </aside>
      <section class="auth-form">
        <slot />
      </section>
    </div>
    <div class="copyright">© 2026 中医电子病历质控与标准化系统 · 课程设计</div>
  </div>
</template>

<script setup>
// 登录 / 注册共用外壳（批A·1.3，方案 §1.3）：
// 左品牌区（细线墨点装饰 / 系统名 / 英文副题 / 版本）+ 右表单区（默认插槽）+ 底部版权行。
// 样式不设 scoped：外壳需要同时作用到插槽内容（各页的表单），
// 但所有选择器都收敛在 .auth-page / .auth-shell 命名空间下，不污染全局。
</script>

<style>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 30px 20px 26px;
  /* 宣纸底 + 极淡墨点纹理（内联 SVG，非 CSS 渐变） */
  background-color: var(--paper);
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='18' height='18'><circle cx='1' cy='1' r='0.9' fill='%232f4639' fill-opacity='0.05'/></svg>");
}

.auth-shell {
  width: 100%;
  max-width: 880px;
  display: flex;
  background: #fffdf9;
  border: 1px solid var(--line);
  min-height: 470px;
}

/* ===== 左品牌区 ===== */
.auth-brand {
  width: 336px;
  flex-shrink: 0;
  background: var(--ink-light);
  border-right: 1px solid var(--line);
  padding: 56px 36px;
  display: flex;
  flex-direction: column;
}
.auth-brand .ornament {
  width: 44px;
  height: 1px;
  background: var(--ink);
  opacity: 0.5;
  position: relative;
  margin-bottom: 28px;
}
.auth-brand .ornament::after {
  content: '';
  position: absolute;
  right: -7px;
  top: -2.5px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ink);
  opacity: 0.5;
}
.auth-brand h2 {
  font-size: 21px;
  line-height: 1.6;
  color: var(--ink);
  font-weight: normal;
  letter-spacing: 2px;
}
.auth-brand .en {
  margin-top: 14px;
  font-size: 11px;
  line-height: 1.75;
  color: var(--ochre);
  letter-spacing: 1.2px;
  text-transform: uppercase;
}
.auth-brand .ver {
  margin-top: auto;
  font-size: 11px;
  color: var(--text-sub);
  letter-spacing: 0.5px;
}

/* ===== 右表单区 ===== */
.auth-form {
  flex: 1;
  padding: 52px 56px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.auth-form h3 {
  font-size: 16px;
  color: var(--ink);
  font-weight: normal;
  letter-spacing: 1px;
}
.auth-form .hint {
  font-size: 12px;
  color: var(--text-sub);
  margin: 7px 0 24px;
}

/* 表单控件对齐原型（覆盖 Element Plus 默认间距） */
.auth-form .el-form-item {
  margin-bottom: 16px;
}
.auth-form .el-form-item__label {
  font-size: 12px;
  color: var(--text-sub);
  letter-spacing: 0.5px;
  padding-bottom: 4px;
  line-height: 1.5;
}
.auth-submit {
  width: 100%;
  letter-spacing: 4px;
  margin-top: 6px;
}
.auth-switch {
  margin-top: 18px;
  font-size: 12px;
  color: var(--text-sub);
}
.auth-switch a {
  color: var(--ink-mid);
  text-decoration: none;
  border-bottom: 1px solid var(--line);
  padding-bottom: 1px;
}

/* U13：演示账号提示（登录页底部小字） */
.auth-demo {
  margin-top: auto;
  padding-top: 20px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
  color: var(--text-sub);
  line-height: 2;
}
.auth-demo b {
  color: var(--ink);
  font-weight: normal;
}
.auth-demo code {
  font-family: Consolas, monospace;
  color: var(--ochre);
}

/* 注册页：角色固定提示（替代 el-alert，与中式主题一致） */
.auth-role-tip {
  border: 1px solid var(--line);
  border-left: 3px solid var(--ochre);
  background: var(--ochre-light);
  padding: 9px 12px;
  font-size: 12px;
  color: #6b5a44;
  margin: 0 0 16px;
}

.copyright {
  margin-top: 22px;
  text-align: center;
  font-size: 11px;
  color: var(--text-sub);
  letter-spacing: 0.5px;
}

/* ===== 窄屏（UX-59）：不再把左品牌区压成顶部横条 =====
   品牌区是纯装饰内容，窄屏下直接隐去，让表单占满宽度；
   外壳同步去掉边框与底色，避免出现「空壳套表单」的观感。 */
@media (max-width: 900px) {
  .auth-brand {
    display: none;
  }
  .auth-shell {
    max-width: 480px;
    min-height: 0;
    border: none;
    background: transparent;
  }
  .auth-form {
    padding: 24px 4px;
  }
  .auth-demo {
    margin-top: 22px;
  }
}
</style>
