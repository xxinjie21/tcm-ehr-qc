import { defineStore } from 'pinia'

/**
 * AI 消费端的跨页状态共享。
 *
 * 生产者是「清洗与导出」页：点详情写 `activeRecord`、跑完 clean 写 `normByLevel`、
 * 清洗状态行同步 `stats`。消费端通过 hasXxx 判空后给降级提示——
 * 这里不编造默认值，没有就是没有。
 */
export const useAiStore = defineStore('ai', {
  state: () => ({
    activeRecord: null,
    normByLevel: null,
    stats: null
  }),
  getters: {
    hasActiveRecord: (state) => state.activeRecord != null,
    hasNormByLevel: (state) => state.normByLevel != null,
    hasStats: (state) => state.stats != null
  },
  actions: {
    setActiveRecord(record) {
      this.activeRecord = record
    },
    setNormByLevel(normByLevel) {
      this.normByLevel = normByLevel
    },
    setStats(stats) {
      this.stats = stats
    },
    clear() {
      this.activeRecord = null
      this.normByLevel = null
      this.stats = null
    }
  }
})
