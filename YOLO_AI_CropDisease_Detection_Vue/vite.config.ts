import vue from '@vitejs/plugin-vue';
import { resolve } from 'path';
import { defineConfig, loadEnv, ConfigEnv,transformWithEsbuild } from 'vite';
import ts from 'typescript';
import vueSetupExtend from 'vite-plugin-vue-setup-extend';

const pathResolve = (dir: string) => {
	return resolve(__dirname, '.', dir);
};

const alias: Record<string, string> = {
	'/@': pathResolve('./src/'),
	'vue-i18n': 'vue-i18n/dist/vue-i18n.cjs.js',
};

const viteConfig = defineConfig((mode: ConfigEnv) => {
	const env = loadEnv(mode.mode, process.cwd());
    // Vite 4 recursively scans every output directory for tsconfig. On this disk old dist/lib
    // is unreadable. Read the actual project configuration explicitly, without scanning artifacts.
    const parsed=ts.readConfigFile(pathResolve('tsconfig.json'),ts.sys.readFile);
    if(parsed.error)throw new Error('无法读取项目tsconfig.json');
    const tsconfigRaw=JSON.stringify(parsed.config);
	return {
		plugins: [vue(), vueSetupExtend(),{
            name:'agriculture-explicit-typescript',enforce:'post',
            async transform(code,id){
                if(id.includes('node_modules')||id.includes('type=style'))return null;
                if(/\.[cm]?tsx?(?:$|\?)/.test(id)||id.endsWith('.vue')||id.includes('type=script'))
                    return transformWithEsbuild(code,id,{loader:'ts',target:'esnext',tsconfigRaw,sourcemap:true});
                return null;
            },
            async renderChunk(code,chunk){
                if(mode.command!=='build')return null;
                return transformWithEsbuild(code,chunk.fileName,{loader:'js',target:'es2020',minify:true,tsconfigRaw,sourcemap:true});
            }
        }],
        esbuild:false,
		root: process.cwd(),
		resolve: { alias },
		base: mode.command === 'serve' ? '/' : env.VITE_PUBLIC_PATH,
		optimizeDeps: {
			include: ['element-plus/es/locale/lang/zh-cn', 'element-plus/es/locale/lang/en', 'element-plus/es/locale/lang/zh-tw'],
		},
		server: {
			host: '0.0.0.0',
			port: env.VITE_PORT as unknown as number,
			open: env.VITE_OPEN,
			hmr: true,
            watch:{ignored:['**/dist/**','**/dist-current/**','**/dist-m3*/**']},
			proxy: {
				'/api': {
					//设置拦截器  拦截器格式   斜杠+拦截器名字，名字可以自己定
					target: 'http://localhost:9999/', //代理的目标地址
					ws: true,
					changeOrigin: true,
					rewrite: (path) => path.replace(/^\/api/, ''),
				},
				'/flask': {
					//设置拦截器  拦截器格式   斜杠+拦截器名字，名字可以自己定
					target: 'http://localhost:5000/', //代理的目标地址
					ws: true,
					changeOrigin: true,
					rewrite: (path) => path.replace(/^\/flask/, ''),
				},
			},
		},
		build: {
            // Fresh output avoids the old unreadable dist residue on E:.
			outDir: process.env.AGRICULTURE_BUILD_DIR || 'dist-current',
            emptyOutDir:false,
			chunkSizeWarningLimit: 1500,
			rollupOptions: {
				output: {
					entryFileNames: `assets/[name].[hash].js`,
					chunkFileNames: `assets/[name].[hash].js`,
					assetFileNames: `assets/[name].[hash].[ext]`,
					compact: true,
					manualChunks: {
						vue: ['vue', 'vue-router', 'pinia'],
						echarts: ['echarts'],
					},
				},
			},
		},
		css: { preprocessorOptions: { css: { charset: false } } },
		define: {
			__VUE_I18N_LEGACY_API__: JSON.stringify(false),
			__VUE_I18N_FULL_INSTALL__: JSON.stringify(false),
			__INTLIFY_PROD_DEVTOOLS__: JSON.stringify(false),
			__VERSION__: JSON.stringify(process.env.npm_package_version),
		},
	};
});

export default viteConfig;
