const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const runtime = path.resolve(__dirname, '..', '..', '..', '..', '.runtime', 'playwright', 'node_modules');
const { chromium } = require(path.join(runtime, 'playwright-core'));
const { PNG } = require(path.join(runtime, 'pngjs'));

const output = path.resolve(__dirname, '..', '..', 'assets', 'greenhouse', 'review');
fs.mkdirSync(output, { recursive: true });

function inspectPixels(file) {
	const image = PNG.sync.read(fs.readFileSync(file));
	let sum = 0;
	let sumSquares = 0;
	let count = 0;
	const colors = new Set();
	for (let y = 16; y < image.height - 16; y += 12) {
		for (let x = 16; x < image.width - 16; x += 12) {
			const offset = (y * image.width + x) * 4;
			const luminance = (image.data[offset] * 0.2126) + (image.data[offset + 1] * 0.7152) + (image.data[offset + 2] * 0.0722);
			sum += luminance;
			sumSquares += luminance * luminance;
			colors.add(`${image.data[offset] >> 4},${image.data[offset + 1] >> 4},${image.data[offset + 2] >> 4}`);
			count += 1;
		}
	}
	const standardDeviation = Math.sqrt(sumSquares / count - (sum / count) ** 2);
	return { width: image.width, height: image.height, standardDeviation: Math.round(standardDeviation), colors: colors.size };
}

async function main() {
	const browser = await chromium.launch({
		executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
		headless: true,
		args: ['--enable-webgl', '--use-angle=swiftshader'],
	});
	try {
		for (const scenario of [
			{ name: 'desktop-exterior', size: { width: 1440, height: 900 }, preset: '外部' },
			{ name: 'desktop-interior', size: { width: 1440, height: 900 }, preset: '通道' },
			{ name: 'mobile-exterior', size: { width: 390, height: 844 }, preset: '外部' },
		]) {
			const page = await browser.newPage({ viewport: scenario.size, deviceScaleFactor: 1 });
			const errors = [];
			page.on('pageerror', (error) => errors.push(error.message));
			page.on('console', (message) => {
				if (message.type() === 'error') errors.push(message.text());
			});
			await page.goto('http://127.0.0.1:5176/greenhouse-workbench.html', { waitUntil: 'domcontentloaded' });
			await page.waitForFunction(() => document.querySelector('#status')?.textContent?.includes('已加载 Blender 模型'), { timeout: 30000 });
			await page.getByRole('button', { name: scenario.preset }).click();
			await page.waitForTimeout(1400);
			if (scenario.name === 'desktop-interior') await page.locator('#demo-devices').check();
			await page.waitForTimeout(700);
			const pageFile = path.join(output, `${scenario.name}.png`);
			const canvasFile = path.join(output, `${scenario.name}-canvas.png`);
			await page.screenshot({ path: pageFile });
			await page.locator('#viewport').screenshot({ path: canvasFile });
			const pixels = inspectPixels(canvasFile);
			const metrics = await page.locator('#metrics').innerText();
			const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
			assert.equal(overflow, false, `${scenario.name} has horizontal overflow`);
			assert.ok(pixels.standardDeviation > 12 && pixels.colors > 20, `${scenario.name} canvas is blank or nearly uniform: ${JSON.stringify(pixels)}`);
			assert.deepEqual(errors, [], `${scenario.name} browser errors`);
			if (scenario.name === 'desktop-exterior') {
				await page.locator('#toggle-panel').click();
				assert.equal(await page.locator('#panel').isVisible(), false);
				await page.locator('#toggle-panel').click();
				assert.equal(await page.locator('#panel').isVisible(), true);
			}
			console.log(JSON.stringify({ scenario: scenario.name, pixels, metrics, pageFile }));
			await page.close();
		}
	} finally {
		await browser.close();
	}
}

main().catch((error) => {
	console.error(error);
	process.exitCode = 1;
});
