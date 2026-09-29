// 引擎节奏（前端镜像）
// 数值镜像后端 src/main/java/.../game/constants/GameConstants.java 的同名常量：
// 后端改节奏时这里同步改，否则倒计时与分时轮询会和实际跳速不一致。
// 一交易日 = 90 跳 × 1 秒 + 午休 10 秒 = 100 秒；全剧 = 24 交易日/年 × 3 年 = 72 天 ≈ 2 小时。

// 每跳毫秒数（= GameConstants.TICK_MS）：分时轮询间隔跟着它走
export const TICK_MS = 1000
// 每跳秒数：把后端下发的"剩余跳数"换算成倒计时秒
export const TICK_SECONDS = TICK_MS / 1000
// 全剧交易日总数（= GameConstants.TOTAL_DAYS）：曲线标题与终局文案用
export const TOTAL_DAYS = 72
