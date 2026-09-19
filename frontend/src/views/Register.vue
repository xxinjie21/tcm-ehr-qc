<template>
  <AuthShell>
    <h3>注册新账号</h3>
    <p class="hint">注册后可直接登录系统</p>

    <div class="auth-role-tip">注册账号角色为「审核员」，管理员账号由系统预置。</div>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          placeholder="2~20 位，字母 / 数字 / 下划线 / 中文"
          size="large"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="密码（至少 6 位）"
          size="large"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          placeholder="请再次输入密码"
          size="large"
          show-password
          autocomplete="new-password"
          @keyup.enter="handleRegister"
        />
      </el-form-item>
      <el-button
        type="primary"
        size="large"
        class="auth-submit"
        :loading="loading"
        @click="handleRegister"
      >
        注 册
      </el-button>
    </el-form>

    <div class="auth-switch">已有账号？<router-link to="/login">返回登录</router-link></div>
  </AuthShell>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { register } from '@/api/auth'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({ username: '', password: '', confirmPassword: '' })

const validateConfirm = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    // whitespace: true 让纯空白按「空」处理，与后端 @NotBlank 的判定口径一致
    { required: true, whitespace: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 20, message: '用户名长度须为 2~20 个字符', trigger: 'blur' },
    {
      // 字符集与后端 RegisterDTO.USERNAME_PATTERN 一致（后端那份额外放行空白，为的是让 @NotBlank 报错更准确）
      pattern: /^[A-Za-z0-9_\u4e00-\u9fa5]+$/,
      message: '用户名只能包含字母、数字、下划线或中文',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

const handleRegister = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    // 仅提交用户名与密码；角色由后端固定为审核员
    await register({ username: form.username, password: form.password })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch {
    // 拦截器已提示（如用户名已存在）
  } finally {
    loading.value = false
  }
}
</script>
