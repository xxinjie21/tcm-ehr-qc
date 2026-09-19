<template>
  <AuthShell>
    <h3>登 录</h3>
    <p class="hint">请使用系统分配的账号登录</p>

    <!-- 回车提交提到表单容器，任一输入框回车都生效（UX-43） -->
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          placeholder="请输入用户名"
          size="large"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          ref="pwdRef"
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          size="large"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>
      <el-button
        type="primary"
        size="large"
        class="auth-submit"
        :loading="loading"
        @click="handleLogin"
      >
        登 录
      </el-button>
    </el-form>

    <div class="auth-switch">还没有账号？<router-link to="/register">立即注册</router-link></div>

    <!-- U13：演示账号提示 -->
    <div class="auth-demo">
      <b>演示账号</b>　管理员 <code>admin / 123456</code>　｜　审核员 <code>auditor / 123456</code>
    </div>
  </AuthShell>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const pwdRef = ref(null)
const loading = ref(false)

// 注册成功跳转时会带 ?username=，回填后只需输入密码（UX-42）
const form = reactive({
  username: (route.query.username || '').toString(),
  password: ''
})

onMounted(() => {
  if (form.username) nextTick(() => pwdRef.value?.focus())
})
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/** 只接受站内路径，避免 ?redirect= 被用作开放重定向（UX-07） */
const safeRedirect = () => {
  const target = route.query.redirect
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    ? target
    : '/dashboard'
}

const handleLogin = async () => {
  // validate() 失败会 reject，未捕获会产生未处理的 Promise 异常（UX-26）
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await login(form)
    userStore.setLogin(res.data)
    ElMessage.success('登录成功')
    // 还原登录前的目标页（如会话过期时被中断的页面）（UX-07）
    router.push(safeRedirect())
  } catch {
    // 拦截器已提示（凭证错误 / 网络异常）
  } finally {
    loading.value = false
  }
}
</script>
