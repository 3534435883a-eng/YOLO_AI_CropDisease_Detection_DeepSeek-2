<template>
	<div class="personal-container layout-padding">
		<div class="personal-wrapper">
			<header class="manage-heading">
				<span class="manage-kicker">WORKSPACE · PROFILE</span>
				<h1>个人设置</h1>
				<p>更新账号资料与头像，修改完成后会同步到当前账户。</p>
			</header>
			<div class="content-wrapper">
				<div class="left-section">
					<div class="avatar-card">
						<div class="avatar-wrapper">
							<el-upload
								v-model="state.form.avatar"
								ref="uploadFile"
								class="avatar-uploader"
								action="http://localhost:9999/files/upload"
								:show-file-list="false"
								:on-success="handleAvatarSuccessone"
							>
								<div class="avatar-content">
									<img v-if="imageUrl" :src="imageUrl" class="avatar-image" />
									<div v-else class="avatar-placeholder">
										<el-icon class="upload-icon"><Plus /></el-icon>
										<span>点击上传头像</span>
									</div>
								</div>
							</el-upload>
						</div>
						<div class="profile-caption">当前账户</div>
						<h3 class="welcome-text">{{ state.form.name || state.form.username || '平台用户' }}</h3>
						<div class="user-role">{{ state.form.role }}</div>
					</div>
				</div>

				<div class="right-section">
					<div class="info-card">
						<div class="section-heading">
							<div>
								<span class="section-index">ACCOUNT DETAILS</span>
								<h2 class="section-title">个人信息</h2>
							</div>
							<span class="section-note">带 * 的项目请填写完整</span>
						</div>
						<el-form 
							ref="formRef"
							:model="state.form" 
							:rules="rules"
							label-width="100px"
							class="info-form"
						>
							<div class="form-grid">
								<el-form-item label="账号" prop="username">
									<el-input v-model="state.form.username" placeholder="请输入账号">
										<template #prefix>
											<el-icon><User /></el-icon>
										</template>
									</el-input>
								</el-form-item>
								<el-form-item label="密码" prop="password">
									<el-input 
										v-model="state.form.password" 
										type="password" 
										placeholder="请输入密码"
										show-password
									>
										<template #prefix>
											<el-icon><Lock /></el-icon>
										</template>
									</el-input>
								</el-form-item>
								<el-form-item label="姓名" prop="name">
									<el-input v-model="state.form.name" placeholder="请输入姓名">
										<template #prefix>
											<el-icon><UserFilled /></el-icon>
										</template>
									</el-input>
								</el-form-item>
								<el-form-item label="性别" prop="sex">
									<el-select v-model="state.form.sex" placeholder="请选择性别">
										<template #prefix>
											<el-icon><UserFilled /></el-icon>
										</template>
										<el-option label="男" value="男" />
										<el-option label="女" value="女" />
									</el-select>
								</el-form-item>
								<el-form-item label="Email" prop="email">
									<el-input v-model="state.form.email" placeholder="请输入Email">
										<template #prefix>
											<el-icon><Message /></el-icon>
										</template>
									</el-input>
								</el-form-item>
								<el-form-item label="手机号码" prop="tel">
									<el-input v-model="state.form.tel" placeholder="请输入手机号码">
										<template #prefix>
											<el-icon><Phone /></el-icon>
										</template>
									</el-input>
								</el-form-item>
							</div>
							<div class="form-footer">
								<el-button type="primary" @click="submitForm" :icon="Check" class="submit-button">
									确认修改
								</el-button>
							</div>
						</el-form>
					</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts" name="personal">
import { reactive, ref, onMounted } from 'vue';
import type { UploadInstance, UploadProps, FormInstance } from 'element-plus';
import { ElMessage } from 'element-plus';
import request from '/@/utils/request';
import { useUserInfo } from '/@/stores/userInfo';
import { storeToRefs } from 'pinia';
import { Plus, Check, User, Lock, UserFilled, Message, Phone } from '@element-plus/icons-vue';

const imageUrl = ref('');
const uploadFile = ref<UploadInstance>();
const formRef = ref<FormInstance>();

// 表单验证规则
const rules = {
	username: [
		{ required: true, message: '请输入账号', trigger: 'blur' },
		{ min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
	],
	password: [
		{ required: true, message: '请输入密码', trigger: 'blur' },
		{ min: 3, max: 20, message: '长度在 3 到 20 个字符', trigger: 'blur' }
	],
	name: [
		{ required: true, message: '请输入姓名', trigger: 'blur' }
	],
	sex: [
		{ required: true, message: '请选择性别', trigger: 'change' }
	],
	email: [
		{ required: true, message: '请输入邮箱地址', trigger: 'blur' },
		{ type: 'email', message: '请输入正确的邮箱地址', trigger: 'blur' }
	],
	tel: [
		{ required: true, message: '请输入手机号码', trigger: 'blur' },
		// { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号码', trigger: 'blur' }
	]
};

const handleAvatarSuccessone: UploadProps['onSuccess'] = (response, uploadFile) => {
	imageUrl.value = URL.createObjectURL(uploadFile.raw!);
	state.form.avatar = response.data;
};

const state = reactive({
	form: {} as any,
});

const stores = useUserInfo();
const { userInfos } = storeToRefs(stores);

const getTableData = () => {
	request.get('/api/user/' + userInfos.value.userName).then((res) => {
		if (res.code == 0) {
			state.form = res.data;
			if (state.form['role'] == 'admin') {
				state.form['role'] = '管理员';
			} else if (state.form['role'] == 'common') {
				state.form['role'] = '普通用户';
			} else if (state.form['role'] == 'others') {
				state.form['role'] = '其他用户';
			}
			imageUrl.value = state.form.avatar;
		} else {
			ElMessage({
				type: 'error',
				message: res.msg,
			});
		}
	});
};

const submitForm = async () => {
	if (!formRef.value) return;
	
	await formRef.value.validate((valid, fields) => {
		if (valid) {
			upData();
		} else {
			ElMessage.error('请完善必填信息');
			return false;
		}
	});
};

const upData = () => {
	if (state.form['role'] == '管理员') {
		state.form['role'] = 'admin';
	} else if (state.form['role'] == '普通用户') {
		state.form['role'] = 'common';
	} else if (state.form['role'] == '其他用户') {
		state.form['role'] = 'others';
	}
	request.post('/api/user/update', state.form).then((res) => {
		if (res.code == 0) {
			ElMessage.success('修改成功！');
		} else {
			ElMessage({
				type: 'error',
				message: res.msg,
			});
		}
	});
	setTimeout(() => {
		getTableData();
	}, 200);
};

onMounted(() => {
	getTableData();
});
</script>

<style scoped lang="scss">
.personal-container {
	.personal-wrapper {
		width: min(1120px, 100%);
		min-height: 100%;
		margin: 0 auto;
		padding: 22px 24px;
		display: flex;
		flex-direction: column;
		.manage-heading {
			margin-bottom: 18px;
			.manage-kicker { color: var(--agri-olive); font-size: 10px; font-weight: 700; letter-spacing: .16em; }
			h1 { margin: 5px 0 4px; color: var(--agri-wood); font: 600 26px/1.2 Georgia, 'Songti SC', 'SimSun', serif; }
			p { margin: 0; color: var(--agri-muted); font-size: 13px; }
		}
		.content-wrapper {
			flex: 1;
			min-height: 0;
			display: grid;
			grid-template-columns: 250px minmax(0, 1fr);
			gap: 18px;
			.left-section, .right-section { min-width: 0; }
			.avatar-card, .info-card {
				background: var(--agri-surface);
				border: 1px solid var(--agri-line);
				border-radius: 7px;
			}
			.avatar-card { padding: 30px 20px; text-align: center; }
			.avatar-wrapper { margin-bottom: 18px; }
			.avatar-uploader .avatar-content {
				width: 132px; height: 132px; margin: 0 auto; overflow: hidden; cursor: pointer;
				border-radius: 50%; border: 4px solid #fffdf7; background: #f1eadc;
				box-shadow: 0 0 0 1px var(--agri-line);
				.avatar-image { width: 100%; height: 100%; object-fit: cover; }
				.avatar-placeholder { height: 100%; display: flex; flex-direction: column; justify-content: center; align-items: center; color: var(--agri-muted); }
				.upload-icon { font-size: 27px; margin-bottom: 8px; color: var(--agri-olive); }
				span { font-size: 12px; }
			}
			.profile-caption { color: var(--agri-muted); font-size: 12px; }
			.welcome-text { margin: 5px 0 14px; color: var(--agri-wood); font: 600 20px/1.35 Georgia, 'Songti SC', 'SimSun', serif; word-break: break-word; }
			.user-role { display: inline-flex; padding: 5px 12px; border: 1px solid #c9d1bd; border-radius: 20px; background: #eef0e7; color: #58684e; font-size: 12px; }
			.info-card { min-height: 100%; padding: 25px 28px; }
			.section-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: 22px; padding-bottom: 14px; border-bottom: 1px solid var(--agri-line); }
			.section-index { color: var(--agri-olive); font-size: 10px; font-weight: 700; letter-spacing: .13em; }
			.section-title { margin: 4px 0 0; color: var(--agri-wood); font: 600 21px/1.25 Georgia, 'Songti SC', 'SimSun', serif; }
			.section-note { color: var(--agri-muted); font-size: 12px; }
			.info-form {
				.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 24px; row-gap: 8px; }
				:deep(.el-form-item) { margin-bottom: 10px; }
				:deep(.el-form-item__label) { color: var(--agri-ink); font-weight: 500; }
				:deep(.el-input__wrapper), :deep(.el-select__wrapper) { min-height: 38px; border-radius: 4px; }
				:deep(.el-input__prefix) { color: var(--agri-muted); }
				.el-select { width: 100%; }
				.form-footer { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--agri-line); text-align: right; }
				.submit-button { min-width: 124px; }
			}
		}
	}
}

@media (max-width: 900px) {
	.personal-container .personal-wrapper { padding: 18px; }
	.personal-container .content-wrapper { grid-template-columns: 210px minmax(0, 1fr); gap: 12px; }
	.personal-container .info-card { padding: 20px; }
}

@media (max-width: 700px) {
	.personal-container { overflow: auto; }
	.personal-container .content-wrapper { grid-template-columns: 1fr; }
	.personal-container .avatar-card { padding: 20px; }
	.personal-container .info-form .form-grid { grid-template-columns: 1fr; row-gap: 0; }
	.personal-container .section-heading { align-items: flex-start; flex-direction: column; }
}
</style>
