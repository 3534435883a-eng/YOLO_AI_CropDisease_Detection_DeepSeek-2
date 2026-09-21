# 部署发现

- 项目根目录：`E:\agent 农业\YOLO_AI_CropDisease_Detection_DeepSeek-2\YOLO_AI_CropDisease_Detection_DeepSeek-2`
- 组件：`YOLO_AI_CropDisease_Detection_Flask`、`YOLO_AI_CropDisease_Detection_SpringBoot`、`YOLO_AI_CropDisease_Detection_Vue`
- 已存在 Vue `dist` 构建产物，待确认是否可以直接静态部署。
- 已存在 `cropdisease.sql` 与部署教程 DOCX。
- 部署教程要求 Python 3.12、PyTorch、ultralytics、Flask、Flask-SocketIO、FFmpeg、MySQL 8+、Java/Spring Boot、Node/Vue。
- 当前代码配置：Spring Boot `9999`，Flask `5000`，数据库 `localhost:3306/cropdisease`。
- 当前 E 盘已定位到 Python 3.12：`E:\Python-Migrated\Python312\python.exe`；项目内已包含 FFmpeg 和 YOLO 权重。
- 在 E 盘递归搜索暂未找到 `java.exe`、Maven、MySQL、`npm.exe`，且当前没有发现目标端口监听。
- Vue `src/utils/api.ts` 中仍有外部地址 `http://1.15.180.194:9090`，需确认是否为无关遗留配置。
