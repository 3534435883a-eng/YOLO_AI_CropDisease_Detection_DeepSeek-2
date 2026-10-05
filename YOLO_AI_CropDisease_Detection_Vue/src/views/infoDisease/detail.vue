<template>
	<div>
		<el-dialog
			v-model="state.isShowDialog"
			width="1200px"
			draggable
			:close-on-click-modal="false"
			:close-on-press-escape="false"
		>
			<div class="disease-detail">
				<div class="detail-header">
					<p class="eyebrow">DISEASE FIELD NOTE · {{ state.disease.cropType || '知识条目' }}</p>
					<div class="title">{{ state.disease.name }}</div>
					<p class="detail-subtitle">病害表现与防治信息 · 用于知识核对参考</p>
				</div>
				<div class="detail-content">
					<div class="content-left">
						<div class="section">
							<div class="section-title">
								<el-icon><ele-Warning /></el-icon>
								为害症状
							</div>
							<div class="section-content">{{ state.disease.symptoms }}</div>
						</div>
						<div class="section">
							<div class="section-title">
								<el-icon><ele-InfoFilled /></el-icon>
								发生因素
							</div>
							<div class="section-content">{{ state.disease.causes }}</div>
						</div>
						<div class="section">
							<div class="section-title">
								<el-icon><ele-Operation /></el-icon>
								防治方法
							</div>
							<div class="section-content">{{ state.disease.prevention }}</div>
						</div>
					</div>
					<div class="content-right">
						<div class="section">
							<div class="section-title">
								<el-icon><ele-Picture /></el-icon>
								案例图片
							</div>
							<div class="section-content image-content">
								<el-image
									style="width: 400px; height: 400px; border-radius: 8px;"
									:src="state.disease.image"
									:preview-src-list="[state.disease.image]"
									:preview-teleported="true"
									:hide-on-click-modal="true"
									fit="cover"
									:preview-options="{
										zoom: false,
										closeOnPressEscape: true,
										toolbar: false
									}"
								/>
							</div>
						</div>
					</div>
				</div>
			</div>
		</el-dialog>
	</div>
</template>

<script setup lang="ts" name="diseaseDetail">
import { reactive } from 'vue';

// 定义变量内容
const state = reactive({
	isShowDialog: false,
	disease: {} as any,
});

// 打开弹窗
const openDialog = (row: any) => {
	state.disease = row;
	state.isShowDialog = true;
};

// 暴露方法
defineExpose({ openDialog });
</script>

<style scoped lang="scss">
.disease-detail {
	padding: 8px 20px 22px;
	.detail-header {
		text-align: left;
		margin-bottom: 22px;
		padding-bottom: 15px;
		border-bottom: 1px solid #e8dfd4;
		.eyebrow { margin:0 0 7px; color:#9a755a; font-size:10px; font-weight:700; letter-spacing:.12em; }
		.title {
			font:600 28px/1.3 Georgia,'Songti SC',serif;
			color:#382b25;
		}
		.detail-subtitle { margin:5px 0 0; color:#81766c; font-size:12px; }
	}
	.detail-content {
		display: flex;
		gap: 24px;
		.content-left {
			flex: 1;
			.section {
				margin-bottom: 18px;
				.section-title {
					font-size: 15px;
					font-weight: 650;
					color: #49372c;
					margin-bottom: 9px;
					display: flex;
					align-items: center;
					.el-icon {
						margin-right: 8px;
						font-size: 22px;
							color: #a15d40;
					}
				}
				.section-content {
					font-size: 14px;
					color: #65594f;
					line-height: 1.8;
					text-align: justify;
					padding: 14px 16px;
					background: #f8f4ed;
					border:1px solid #ebe1d5;
					border-radius: 3px;
				}
			}
		}
		.content-right {
			width: min(38%, 380px);
			.section {
				.section-title {
					font-size: 15px;
					font-weight: 650;
					color: #49372c;
					margin-bottom: 9px;
					display: flex;
					align-items: center;
					.el-icon {
						margin-right: 8px;
						font-size: 22px;
							color: #a15d40;
					}
				}
				.image-content {
					display: flex;
					justify-content: center;
					align-items: center;
					padding: 12px;
					background: #f8f4ed;
					border:1px solid #ebe1d5;
					border-radius: 3px;
					:deep(.el-image) { width:100% !important; height:360px !important; border-radius:2px !important; }
				}
			}
		}
	}
}
:deep(.el-dialog) { max-width:calc(100vw - 28px); border-radius:4px; background:#fffdf9; }
@media(max-width:850px) { .disease-detail .detail-content { flex-direction:column; }.disease-detail .detail-content .content-right { width:100%; }.disease-detail .detail-content .content-right .section .image-content :deep(.el-image) { height:min(60vw,360px) !important; } }
</style>
