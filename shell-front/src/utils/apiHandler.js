/**
 * 通用API响应处理工具
 * 专门处理统一格式的API响应
 * 格式: { code, message, data, success, exceptionType, externMessage }
 */

import {ElMessage} from 'element-plus'
import axios from 'axios'

// HTTP、下载、流式请求和 WebSocket 都使用同源根路径，开发时交给 Vite 代理。
const API_BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '')

// API 基础配置
const API_CONFIG = {
    baseURL: API_BASE,
    timeout: 60000, // 增加到 60 秒，适配 AI 响应时间
    headers: {
        'Content-Type': 'application/json'
    }
}

// 创建axios实例
const apiClient = axios.create(API_CONFIG)

// 默认 headers 含 application/json；上传 FormData 时必须去掉 Content-Type，否则后端报「not a multipart request」
apiClient.interceptors.request.use((config) => {
    if (config.data instanceof FormData && config.headers) {
        const h = config.headers
        if (typeof h.delete === 'function') {
            h.delete('Content-Type')
            h.delete('content-type')
        } else {
            delete h['Content-Type']
            delete h['content-type']
        }
    }
    return config
})

/**
 * 统一API响应处理器
 * 专门处理格式: { code, message, data, success, exceptionType, externMessage }
 * @param {Promise} apiPromise - API调用的Promise
 * @param {Object} options - 配置选项
 * @returns {Promise} 处理后的Promise
 */
export async function handleApiResponse(apiPromise, options = {}) {
    const {
        showSuccess = false,        // 是否显示成功消息
        successMessage = '操作成功', // 自定义成功消息
        showFailure = true,         // 是否显示失败消息
        parseJsonData = true,       // 是否自动解析JSON字符串数据
        throwOnError = true,        // 是否在错误时抛出异常
        silent = false             // 是否静默处理（不显示任何消息）
    } = options

    try {
        const response = await apiPromise

        // 检查响应格式
        if (!response || typeof response !== 'object') {
            throw new Error('无效的响应格式')
        }

        const {code, success, message, data, exceptionType, externMessage} = response.data
        if (success !== true || code !== 200) {
            const extInfo = externMessage ? (": " + externMessage) : "";
            const errorMsg = (message + extInfo) || '操作失败'
            if (throwOnError) {
                const error = new Error(errorMsg)
                error.code = code
                error.exceptionType = exceptionType
                throw error
            }
            if (!silent && showFailure) {
                ElMessage.error(errorMsg)
            }
            return null
        }

        // 显示成功消息
        if (!silent && showSuccess) {
            ElMessage.success(successMessage)
        }

        // 处理数据
        let resultData = data
        // 如果data是JSON字符串且需要解析
        if (parseJsonData && typeof data === 'string') {
            // data 可能是分页 JSON 字符串；去掉 BOM，短对象如 "{}" 也需解析
            const trimmedData = data.trim().replace(/^\uFEFF/, '')
            if (trimmedData.startsWith('{') || trimmedData.startsWith('[')) {
                try {
                    resultData = JSON.parse(trimmedData)
                } catch (parseError) {
                    console.error('JSON解析失败:', parseError)
                    console.error('原始数据:', data)
                }
            }
        }

        return resultData

    } catch (error) {
        // 网络错误或其他异常
        if (error.isAxiosError) {
            // Axios网络错误
            let errorMsg = '网络请求失败'
            if (error.response) {
                // 服务器响应了错误状态
                errorMsg = `服务器错误 (${error.response.status})`
            } else if (error.request) {
                // 请求已发出但没有收到响应
                errorMsg = '服务器无响应'
            } else {
                // 请求配置出错
                errorMsg = error.message
            }

            if (!silent && showFailure) {
                ElMessage.error(errorMsg)
            }
        } else if (!silent && showFailure) {
            // 业务逻辑错误
            ElMessage.error(error.message || '操作失败')
        }

        if (throwOnError) {
            throw error
        }
        return null
    }
}

/**
 * GET请求封装
 */
export function get(url, params = {}, options = {}) {
    return handleApiResponse(
        apiClient.get(url, {params}),
        options
    )
}

/**
 * POST 请求封装
 */
export function post(url, data = {}, options = {}) {
    // 如果数据是 FormData 实例，需要移除默认的 Content-Type
    // 让浏览器自动设置 multipart/form-data 和 boundary
    if (data instanceof FormData) {
        options.headers = {
            ...options.headers,
            'Content-Type': undefined  // undefined 会让 axios 自动处理
        }
    }

    return handleApiResponse(
        apiClient.post(url, data, options),  // ✅ 传递 options 参数
        {showSuccess: true, ...options}
    )
}

/**
 * PUT请求封装
 */
export function put(url, data = {}, options = {}) {
    return handleApiResponse(
        apiClient.put(url, data),
        {showSuccess: true, ...options}
    )
}

/**
 * DELETE请求封装
 */
export function del(url, options = {}) {
    return handleApiResponse(
        apiClient.delete(url),
        {showSuccess: true, ...options}
    )
}

/**
 * PATCH请求封装
 */
export function patch(url, data = {}, options = {}) {
    return handleApiResponse(
        apiClient.patch(url, data),
        {showSuccess: true, ...options}
    )
}

// 导出 axios 实例与根地址（流式 fetch 等需自行拼 URL 时使用）
export {apiClient, API_BASE}

// 默认导出
export default {
    get,
    post,
    put,
    delete: del,
    patch,
    handleApiResponse,
    apiClient
}
