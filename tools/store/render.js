// Feature graphic (1024×500) from the app's own art: sprite + name + tagline, in English and Russian.
// Run from the repo root: node tools/store/render.js (the 512 px icon: python3 -I tools/store/icon.py)
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

function vectorToSvg(xmlPath, bg) {
  const xml = fs.readFileSync(xmlPath, 'utf8');
  const vw = /viewportWidth="([\d.]+)"/.exec(xml)[1], vh = /viewportHeight="([\d.]+)"/.exec(xml)[1];
  const col = c => '#' + c.slice(3); // #AARRGGBB → #RRGGBB
  const paths = [...xml.matchAll(/<path([^>]*)\/>/g)].map(m => {
    const a = m[1];
    const d = /pathData="([^"]+)"/.exec(a)[1];
    const fill = /fillColor="([^"]+)"/.exec(a), stroke = /strokeColor="([^"]+)"/.exec(a), sw = /strokeWidth="([^"]+)"/.exec(a);
    return `<path d="${d}" fill="${fill ? col(fill[1]) : 'none'}"${stroke ? ` stroke="${col(stroke[1])}" stroke-width="${sw ? sw[1] : 1}" stroke-linecap="round"` : ''}/>`;
  }).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${vw} ${vh}" width="512" height="512"><rect width="100%" height="100%" fill="${bg}"/>${paths}</svg>`;
}

const sprite = n => 'data:image/webp;base64,' + fs.readFileSync(`app/src/main/res/drawable-nodpi/iskra_${n}.webp`).toString('base64');

function feature(lang) {
  const t = lang === 'ru'
    ? { name: 'Искра', line: 'Котодевочка-компаньон на твоём экране', chips: ['Таймеры', 'Напоминания', 'Заметки', 'Погода', 'Болтовня'] }
    : { name: 'Iskra', line: 'A cat-girl companion on your screen', chips: ['Timers', 'Reminders', 'Notes', 'Weather', 'Chat'] };
  return `<html><body style="margin:0;width:1024px;height:500px;overflow:hidden;font-family:'Noto Sans','DejaVu Sans',sans-serif;
    background:radial-gradient(circle at 25% 55%,#5b3a86 0%,#2a1f3a 62%);color:#f6efff;position:relative">
    <img src="${sprite('wave')}" style="position:absolute;left:40px;top:20px;width:460px;height:460px">
    <img src="${sprite('joy')}" style="position:absolute;left:380px;top:250px;width:170px;height:170px;opacity:.0">
    <div style="position:absolute;left:520px;top:110px;width:470px">
      <div style="font-size:96px;font-weight:800;letter-spacing:1px">${t.name}</div>
      <div style="font-size:34px;line-height:1.25;margin-top:6px;color:#e2d3ff">${t.line}</div>
      <div style="margin-top:28px;display:flex;flex-wrap:wrap;gap:10px">${t.chips.map(c =>
        `<span style="background:#7a4fb0;border-radius:22px;padding:8px 18px;font-size:22px">${c}</span>`).join('')}</div>
    </div></body></html>`;
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  await page.setViewportSize({ width: 1024, height: 500 });
  for (const [l, lang] of [['en-US', 'en'], ['ru-RU', 'ru']]) {
    await page.setContent(feature(lang));
    await page.screenshot({ path: `fastlane/metadata/android/${l}/images/featureGraphic.png` });
  }
  await browser.close();
  console.log('ok');
})();
