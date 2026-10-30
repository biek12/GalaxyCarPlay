#!/usr/bin/env node
/* 本机交互提案回归；浏览器与预览材料不进入 APK，不连接车辆。 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');

function loadPlaywright() {
  if (process.env.GALAXY_PLAYWRIGHT_MODULE) return require(process.env.GALAXY_PLAYWRIGHT_MODULE);
  try { return require('playwright'); } catch {}
  const cache = path.join(os.homedir(), '.npm/_npx');
  for (const entry of fs.existsSync(cache) ? fs.readdirSync(cache) : []) {
    const candidate = path.join(cache, entry, 'node_modules/playwright');
    if (fs.existsSync(path.join(candidate, 'package.json'))) return require(candidate);
  }
  throw Error('缺少本机 playwright；可用 GALAXY_PLAYWRIGHT_MODULE 指定已安装模块');
}
const root = path.resolve(__dirname, '../docs/design/cluster-editor-v1');
const allowed = new Map([
  ['/', ['index.html','text/html']], ['/prototype.css',['prototype.css','text/css']],
  ['/prototype.js',['prototype.js','text/javascript']]
]);
const server = http.createServer((request,response) => {
  const item = allowed.get(request.url);
  if (!item && /^\/catalog\/(?:catalog\.json|style-\d+-(?:day|night)\.png)$/.test(request.url)) {
    const file=path.join(root,request.url.slice(1));
    if(fs.existsSync(file)){response.setHeader('Content-Type',file.endsWith('.json')?'application/json':'image/png');response.end(fs.readFileSync(file));return;}
  }
  if (!item) { response.writeHead(404); response.end(); return; }
  response.setHeader('Content-Type',item[1]+'; charset=utf-8');
  response.end(fs.readFileSync(path.join(root,item[0])));
});

async function main() {
  const {chromium} = loadPlaywright();
  const options = {headless:true};
  if (process.env.GALAXY_BROWSER_EXECUTABLE) options.executablePath=process.env.GALAXY_BROWSER_EXECUTABLE;
  else if (process.platform==='darwin') {
    // 只读取已安装的本机浏览器；不在验证期间联网下载。
    const cache=path.join(os.homedir(),'Library/Caches/ms-playwright');
    for(const entry of fs.existsSync(cache)?fs.readdirSync(cache).reverse():[]){
      if(!entry.startsWith('chromium_headless_shell-'))continue;
      const candidate=path.join(cache,entry,`chrome-headless-shell-mac-${process.arch==='arm64'?'arm64':'x64'}`,'chrome-headless-shell');
      if(fs.existsSync(candidate)){options.executablePath=candidate;break;}
    }
  }
  const browser = await chromium.launch(options);
  const failures=[];
  const context = await browser.newContext({viewport:{width:960,height:1280},deviceScaleFactor:1});
  const page=await context.newPage();
  page.setDefaultTimeout(8000);
  page.on('pageerror',error=>failures.push(error.message));
  const checks=[];
  async function check(name,action){await action();checks.push(name);}
  const input=key=>page.locator(`[data-prop="${key}"]`);
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await check('主题库与竖屏布局',async()=>{
      assert.equal(await page.locator('.theme-tile').count(),3);
      assert.equal(await page.locator('.theme-tile h3').first().textContent(),'简境 · Simple Drive');
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
      await page.screenshot({path:path.join(root,'themes-day.png'),fullPage:true});
    });
    await page.getByRole('button',{name:'编辑当前主题',exact:true}).click();
    await check('拖动使用设计坐标，支持撤销重做',async()=>{
      const box=await page.locator('#edit-preview [data-id="speed"]').boundingBox();
      const before=Number(await input('x').inputValue());
      await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
      await page.mouse.down();await page.mouse.move(box.x+box.width/2+20,box.y+box.height/2,{steps:5});await page.mouse.up();
      const after=Number(await input('x').inputValue());assert.ok(after>before+20);
      await page.locator('#undo').click();assert.equal(Number(await input('x').inputValue()),before);
      await page.locator('#redo').click();assert.equal(Number(await input('x').inputValue()),after);
      await page.locator('#undo').click();
    });
    await check('缩放与越界约束',async()=>{
      const box=await page.locator('.resize-handle').boundingBox();
      const width=Number(await input('w').inputValue());
      await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+12,box.y+box.height/2+8,{steps:4});await page.mouse.up();
      assert.ok(Number(await input('w').inputValue())>width);
      await input('x').fill('9999');await input('x').press('Tab');
      assert.ok(Number(await input('x').inputValue())+Number(await input('w').inputValue())<=1920);
      await page.locator('#undo').click();await page.locator('#undo').click();
    });
    await check('日夜的组件覆盖独立',async()=>{
      await page.getByRole('button',{name:'夜间',exact:true}).last().click();
      await page.getByRole('button',{name:'组件颜色 #40c79a',exact:true}).click();
      assert.equal(await page.getByRole('button',{name:'组件颜色 #40c79a',exact:true}).getAttribute('aria-pressed'),'true');
      await page.getByRole('button',{name:'日间',exact:true}).click();
      assert.equal(await page.getByRole('button',{name:'组件颜色 #1368fb',exact:true}).getAttribute('aria-pressed'),'true');
      await page.screenshot({path:path.join(root,'editor-day.png'),fullPage:true});
      await page.locator('#appearance').click();await page.getByRole('button',{name:'夜间',exact:true}).click();
      await page.screenshot({path:path.join(root,'editor-night.png'),fullPage:true});
      await page.locator('#appearance').click();await page.getByRole('button',{name:'日间',exact:true}).click();
    });
    await check('添加、复制、删除与图层',async()=>{
      await page.getByRole('button',{name:'＋ 添加组件',exact:true}).click();
      await page.locator('#sheet-body [data-add="artwork"]').click();
      assert.equal(await page.locator('#edit-preview .node.artwork').count(),1);
      await page.getByRole('button',{name:'复制',exact:true}).click();assert.equal(await page.locator('#edit-preview .node.artwork').count(),2);
      await page.getByRole('button',{name:'删除',exact:true}).click();assert.equal(await page.locator('#edit-preview .node.artwork').count(),1);
      await page.getByRole('button',{name:'图层',exact:true}).click();await page.locator('[data-top-layer="map"]').click();
      assert.equal(await page.locator('#edit-preview .node').last().getAttribute('data-id'),'map');
      await page.locator('#undo').click();
    });
    await check('背景修改、模板副本与重载恢复',async()=>{
      await page.getByRole('button',{name:'更换背景',exact:true}).click();await page.getByRole('button',{name:'深海',exact:true}).click();
      await page.getByRole('button',{name:'保存',exact:true}).click();
      const stored=await page.evaluate(()=>JSON.parse(localStorage.getItem('autocluster-design-v1')));
      assert.equal(stored.themes.length,1);assert.equal(stored.themes[0].background,'ink');
      assert.equal(stored.themes[0].builtin,false);
      await page.getByRole('button',{name:'应用到仪表',exact:true}).click();
      await page.reload();assert.equal(await page.locator('.theme-tile').count(),4);
      assert.match(await page.locator('#active-name').textContent(),/自定义/);
    });
    await check('显示设置与72个只读样式预览',async()=>{
      await page.getByRole('button',{name:'显示设置',exact:true}).click();
      await page.locator('#fps').selectOption('24');await page.locator('#policy').selectOption('fixed-cap');await page.getByRole('button',{name:'保存设置',exact:true}).click();
      await page.getByRole('button',{name:'显示设置',exact:true}).click();assert.equal(await page.locator('#fps').inputValue(),'24');
      await page.getByRole('button',{name:'关闭面板',exact:true}).click();
      await page.locator('[data-page="components"]').click();
      await page.waitForFunction(()=>document.querySelectorAll('#catalog .catalog-item').length===72);
      assert.equal(await page.locator('#catalog [data-add]').count(),0);
      assert.equal(await page.locator('#catalog img').count(),72);
      assert.equal(await page.locator('#catalog').evaluate(el=>getComputedStyle(el).gridTemplateColumns.split(' ').length),3);
    });
    await check('小屏无横向溢出、无页面异常',async()=>{
      await page.setViewportSize({width:390,height:844});
      for(const next of ['themes','editor','components']){
        await page.locator(`[data-page="${next}"]`).click();
        assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),next+' 横向溢出');
      }
      assert.deepEqual(failures,[]);
    });
    const result={verifiedAt:new Date().toISOString(),timeZone:'Asia/Shanghai',command:'node scripts/verify_cluster_editor.cjs',checks,scope:'本机浏览器交互提案；没有Android运行时、APK或实车验收'};
    fs.writeFileSync(path.join(root,'verification.json'),JSON.stringify(result,null,2)+'\n');
    console.log(`Cluster编辑器提案验证通过：${checks.length}组交互，3张日夜/主题截图。仅本机原型。`);
  } finally { await browser.close(); }
}
server.listen(0,'127.0.0.1',()=>main().then(()=>server.close(),error=>{console.error(error);server.close();process.exitCode=1;}));
