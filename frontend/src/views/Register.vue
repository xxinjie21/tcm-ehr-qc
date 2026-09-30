<template>
  <AuthShell>
    <h3>注册新账号</h3>
    <p class="hint">注册后可直接登录系统</p>

    <div class="auth-role-tip">注册账号为「用户」（在组织内可能是所有者或成员），管理员账号由系统预置。</div>

    <!-- 可选「同时创建组织」：提交后组织立即生效，创建者即所有者 -->
    <el-collapse v-model="form.groupOpen" class="group-collapse">
      <el-collapse-item title="同时创建组织（可选）" name="group">
        <p class="hint">勾选后提交会注册并<b>立即创建组织</b>（无需审批），你成为该组织所有者。不勾选则登录后自行创建，或等待其他组织所有者邀请。</p>
        <el-form-item v-if="form.groupOpen" label="组织编码" prop="groupCode">
          <el-input v-model="form.groupCode" placeholder="可留空；大写字母/数字/连字符，2~50 位" size="large" />
        </el-form-item>
        <el-form-item v-if="form.groupOpen" label="组织名称" prop="groupName">
          <el-input v-model="form.groupName" placeholder="名称最长 100 字" size="large" />
        </el-form-item>
        <el-form-item v-if="form.groupOpen" label="用途说明（可选）">
          <el-input v-model="form.groupPurpose" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-collapse-item>
    </el-collapse>

    <!-- 回车提交提到表单容器，任一输入框回车都生效-->
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

const form = reactive({ username: '', password: '', confirmPassword: '', groupOpen: false, groupCode: '', groupName: '', groupPurpose: '' })

// 密码变了，上一次「确认密码」的一致性结论就失效，需要重新判定
watch(
  () => form.password,
  () => {
    if (form.confirmPassword) {
      formRef.value?.validateField('confirmPassword').catch(() => {})
    }
  }
)

// 「确认密码」自定义校验：空值与两次不一致分别给出对应文案，与后端二次校验呼应
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
  confirmPassword: [{ required: true, validator: validateConfirm, trigger: 'blur' }],
  groupCode: [
    { required: true, whitespace: true, message: '请输加入组织织编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_-]{2,50}$/, message: '编码为 2~50 位字母、数字、短横线或下划线', trigger: 'blur' }
  ],
  groupName: [{ required: true, whitespace: true, message: '请输加入组织织名称', trigger: 'blur' }]
}

// 提交注册：校验通过后只提交用户名与密码（角色由后端固定为「用户」）；
// 成功后带用户名跳回登录页回填，用户只需再输密码
const handleRegister = async () => {
  // 1. 先做表单校验（含确认密码一致性）；失败直接中止
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 2. 置加载态：按钮转圈，避免重复提交
  loading.value = true
  try {
    // 构建请求体：同时创建组织时勾上 createGroup；角色由后端固定为「用户」
    const payload = { username: form.username, password: form.password }
    if (form.groupOpen) {
      payload.createGroup = {
        code: form.groupCode,
        name: form.groupName,
        purpose: form.groupPurpose || undefined
      }
    }
    await register(payload)
    // 3. 提示成功：创建组织请求与单纯注册用不同的口径提示
    ElMessage.success(form.groupOpen ? '注册成功，组织已创建，你是该组织所有者' : '注册成功，请登录后创建或加入组织')
    router.push({ path: '/login', query: { username: form.username } })
  } catch {
    // 拦截器已提示（如用户名已存在）
  } finally {
    // 无论成败都复位加载态
    loading.value = false
  }
}
</script>
