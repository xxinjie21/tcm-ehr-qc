// 分页尺寸候选（P3.2 收敛，各列表页共用同一处）。
// 名字按「档位宽窄」而不是按页面命名：STANDARD 是多数列表，
// WIDE 是条目数可能上千的页（词典等）—— 按页面名命名（PAGE_SIZES_DICTIONARY）
// 会让下一个需要 200 档的页面要么复用错名字、要么再开一个同义常量。
export const PAGE_SIZES_STANDARD = [10, 20, 50]
export const PAGE_SIZES_WIDE = [20, 50, 100, 200]