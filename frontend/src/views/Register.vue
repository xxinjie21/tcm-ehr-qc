<template>
  <div class="register-page">
    <el-card class="register-card">
      <h2 class="register-title">中医电子病历质控与标准化系统</h2>
      <p class="register-sub">注册新账号</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="0">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码（至少 6 位）"
            size="large"
            show-password
          />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认密码"
            size="large"
            show-password
            @keyup.enter="handleRegister"
          />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          class="role-tip"
          title="注册账号角色为「审核员」，管理员账号由系统预置。"
        />
        <el-button
          type="primary"
          size="large"
          style="width: 100%"
          :loading="loading"
          @click="handleRegister"
        >
          注 册
        </el-button>
        <div class="to-login">
          已有账号？<router-link to="/login">返回登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }]
}

const handleRegister = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    // 仅提交用户名与密码；角色由后端固定为审核员
    await register({ username: form.username, password: form.password })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f4e3d 0%, #2d6a4f 100%);
}

.register-card {
  width: 400px;
  padding: 12px 8px;
}

.register-title {
  text-align: center;
  margin-bottom: 8px;
  color: #1f4e3d;
  font-size: 18px;
}

.register-sub {
  text-align: center;
  margin-bottom: 20px;
  color: #7a786f;
  font-size: 13px;
}

.role-tip {
  margin-bottom: 16px;
}

.to-login {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  color: #7a786f;
}

.to-login a {
  color: #2d6a4f;
  text-decoration: none;
}
</style>
