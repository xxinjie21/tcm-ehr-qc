<template>
  <AuthShell>
    <h3>登 录</h3>
    <p class="hint">请使用系统分配的账号登录</p>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
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
          v-model="form.password"
          type="password"
          placeholder="请输入密码"
          size="large"
          show-password
          autocomplete="current-password"
          @keyup.enter="handleLogin"
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
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login(form)
    userStore.setLogin(res.data)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>
