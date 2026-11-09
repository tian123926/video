<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const uid = ref('')
const submitting = ref(false)

const submit = async (): Promise<void> => {
  const value = Number(uid.value)
  if (!Number.isInteger(value) || value <= 0) {
    ui.toast('请输入正确的数字 UID')
    return
  }
  submitting.value = true
  try {
    await auth.loginByUid(value)
    ui.toast(`欢迎回来，${auth.username}`)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/profile'
    await router.replace(redirect)
  } catch (error) {
    ui.toast(error instanceof Error ? error.message : '登录失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-visual">
      <div class="login-visual__bg" aria-hidden="true"></div>

      <p class="eyebrow">
        <span class="eyebrow__dot" aria-hidden="true"></span>
        GUANGYING · 光影放映室
      </p>
      <h1>把夜晚<br />交给好影像。</h1>
      <p class="login-visual__sub">
        一个属于你的私人放映厅。海量片源随时点播，评论陪你一起把故事看完。
      </p>

      <ul class="login-visual__points">
        <li>
          <span class="login-visual__icon"><AppIcon name="play" :size="14" /></span>
          高清点播，打开即看
        </li>
        <li>
          <span class="login-visual__icon"><AppIcon name="comment" :size="14" /></span>
          一级 / 二级评论随时互动
        </li>
        <li>
          <span class="login-visual__icon"><AppIcon name="shorts" :size="14" /></span>
          沉浸刷视频，上下切换
        </li>
      </ul>
    </section>

    <section class="login-panel">
      <div class="login-card">
        <span class="login-card__mark">
          <AppIcon name="user" :size="22" />
        </span>
        <h2>登录光影放映室</h2>
        <p>输入你的 UID 即可登录，无需注册。</p>

        <form class="login-form" @submit.prevent="submit">
          <label class="login-field">
            <span>UID</span>
            <input
              v-model="uid"
              type="number"
              min="1"
              inputmode="numeric"
              placeholder="例如 1"
              autocomplete="off"
            />
          </label>
          <button class="primary-button login-submit" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
        </form>

        <p class="login-hint">UID 由后台用户表分配，爬虫导入的作者与演示用户都可直接登录。</p>
      </div>
    </section>
  </div>
</template>
