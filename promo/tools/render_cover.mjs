import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createRequire } from "node:module";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.resolve(process.argv[2] || path.join(root, "cover-4x3.html"));
const output = path.resolve(process.argv[3] || path.join(root, "artifacts", "Temvio-cover-4x3.png"));
const width = 1600;
const height = 1200;

const require = createRequire(import.meta.url);
const candidates = [
  "playwright",
  process.env.CODEX_PLAYWRIGHT_PATH,
  path.join(os.homedir(), ".cache", "codex-runtimes", "codex-primary-runtime", "dependencies", "node", "node_modules", "playwright"),
].filter(Boolean);
let chromium;
for (const candidate of candidates) {
  try { chromium = require(candidate).chromium; break; } catch { /* try next location */ }
}
if (!chromium) throw new Error("Playwright was not found. Install playwright or set CODEX_PLAYWRIGHT_PATH.");

await fs.mkdir(path.dirname(output), { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.CHROME_PATH || "C:/Program Files/Google/Chrome/Application/chrome.exe",
  headless: true,
  args: ["--allow-file-access-from-files", "--disable-gpu", "--hide-scrollbars", "--font-render-hinting=none"],
});
try {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.goto(pathToFileURL(source).href, { waitUntil: "load" });
  await page.evaluate(async () => {
    if (window.__PROMO_READY) await window.__PROMO_READY;
    await document.fonts.ready;
    await Promise.all(Array.from(document.images).map((img) => img.complete
      ? Promise.resolve()
      : new Promise((resolve) => { img.onload = img.onerror = resolve; })));
  });
  if (!(await page.evaluate(() => typeof window.renderAt === "function"))) {
    throw new Error("Cover source must define window.renderAt(timeInSeconds)");
  }
  await page.evaluate(() => window.renderAt(0));
  await page.screenshot({ path: output });
  process.stdout.write(`Rendered ${width}x${height} cover to ${output}\n`);
} finally {
  await browser.close();
}
