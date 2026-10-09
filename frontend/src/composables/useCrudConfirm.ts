import {ElMessageBox} from 'element-plus'
import {i18n} from '../i18n'

/**
 * 写操作二次确认封装。
 * 所有 POST / PATCH / DELETE 在发起请求前必须调用 confirm()。
 *
 * ⚠️ element-plus 2.14.6 的 MessageBoxData 类型与 confirm 泛型不兼容，
 * 下方 catch 分支用 `as never` 绕过（kafkaVisual5 同款坑位经验）。
 */
export const useCrudConfirm = () => {
    /**
     * @param message 确认正文（支持 \n 换行，用于罗列风险点；由调用方传入已翻译文案）
     * @param title   弹窗标题（默认走语言包 common.crud.confirmTitle）
     * @param type    'warning' 一般写操作 | 'error' 不可逆 / 高危操作
     * @returns true = 用户确认；false = 用户取消
     */
    const confirm = async (
        message: string,
        title?: string,
        type: 'warning' | 'error' = 'warning',
    ): Promise<boolean> => {
        const t = i18n.global.t
        try {
            await ElMessageBox.confirm(message, title ?? t('common.crud.confirmTitle'), {
                confirmButtonText: t('common.confirm'),
                cancelButtonText: t('common.cancel'),
                type,
                // 正文含换行时保留换行为空白符，避免风险提示挤成一行
                dangerouslyUseHTMLString: false,
                customStyle: {whiteSpace: 'pre-line'},
            })
            return true
        } catch {
            // 用户点击取消 / 关闭弹窗
            return false
        }
    }

    return {confirm}
}