/** 趋势图的折线系列定义（TrendChart 与 Dashboard 共享，故独立成文件）。 */
export interface TrendSeries {
    profileId: number
    name: string
    /** 与 timestamps 等长的数值序列 */
    values: number[]
}