<template>
  <AuthShell>
    <h3>注册新账号</h3>
    <p class="hint">注册后可直接登录系统</p>

    <div class="auth-role-tip">注册账号角色为「审核员」，管理员账号由系统预置。</div>

    <!-- 回车提交提到表单容器，任一输入框回车都生效（UX-43） -->
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister">
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
import { ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthShell from '@/components/AuthShell.vue'
import { register } from '@/api/auth'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({ username: '', password: '', confirmPassword: '' })

// 密码变了，上一次「确认密码」的一致性结论就失效，需要重新判定（UX-25）
watch(
  () => form.password,
  () => {
    if (form.confirmPassword) {
      formRef.value?.validateField('confirmPassword').catch(() => {})
    }
  }
)

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
    {
      // 长度与字符集写在同一条正则里，文案与后端 RegisterDTO 保持一致
      pattern: /^[A-Za-z0-9_\u4e00-\u9fa5]{2,20}$/,
      message: '用户名须为 2~20 位字母、数字、下划线或中文',
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
    // 带上用户名回填登录页，用户只需再输密码（UX-42）
    router.push({ path: '/login', query: { username: form.username } })
  } catch {
    // 拦截器已提示（如用户名已存在）
  } finally {
    loading.value = false
  }
}
</script>
