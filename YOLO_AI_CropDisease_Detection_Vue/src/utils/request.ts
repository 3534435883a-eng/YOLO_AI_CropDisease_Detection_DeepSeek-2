import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Session } from '/@/utils/storage';
import qs from 'qs';

// 配置新建一个 axios 实例
const service: AxiosInstance = axios.create({
	baseURL: import.meta.env.VITE_API_DOMAIN,
	timeout: 50000,
	headers: { 'Content-Type': 'application/json;charset=UTF-8' },
	// withCredentials: true,
});

// 添加请求拦截器
service.interceptors.request.use(
	(config: AxiosRequestConfig) => {
		// 在发送请求之前做些什么 token
		if (Session.get('token')) {
			config.headers!['Authorization'] = `${Session.get('token')}`;
		}
		return config;
	},
	(error) => {
		// 对请求错误做些什么
		return Promise.reject(error);
	}
);

// 添加响应拦截器
service.interceptors.response.use(
	(response) => {
		// 对响应数据做点什么
		const res = response.data;
		if (res.code === 401 || res.code === 4001) {
			Session.clear(); // 清除浏览器全部临时缓存
			window.location.href = '/'; // 去登录页
			ElMessageBox.alert('你已被登出，请重新登录', '提示', {})
				.then(() => {})
				.catch(() => {});
		} else {
			return response.data;
		}
	},
	(error) => {
		const status = error.response?.status;
		if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
			ElMessage.error('网络超时');
		} else if (!error.response) {
			ElMessage.error('无法连接服务，请检查后端是否启动');
		} else if (status === 404) {
			ElMessage.error('接口不存在（404）');
		} else if (status >= 500) {
			ElMessage.error(`服务暂时不可用（${status}），请检查后端日志`);
		} else {
			const data = error.response.data;
			const message = data && typeof data === 'object' ? data.msg || data.message : undefined;
			ElMessage.error(message || `请求失败（${status}）`);
		}
		return Promise.reject(error);
	}
);

// 导出 axios 实例
export default service;
