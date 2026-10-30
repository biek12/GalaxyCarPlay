/* 本机交互提案：所有读数为固定演示值，不连接服务、车辆或网络。 */
"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const clone = value => JSON.parse(JSON.stringify(value));
  const escape = value => String(value).replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const catalog = [
    ['map','完整导航地图','CarPlay 原生地图视频；移动、缩放、完整适配','▱'],
    ['value','数字读数','速度、转速、温度及已核验车辆字段','88'],
    ['gauge','指针表盘','启动扫表、实时值平滑，范围可设置','◴'],
    ['ring','圆弧仪表','电量、油量、速度等范围值','◔'],
    ['bar','条形仪表','横向 / 纵向、阈值色、范围绑定','▥'],
    ['tires','四轮胎压','独立四轮读数、单位和数据状态','▦'],
    ['pedals','踏板与方向盘','油门、刹车、方向盘角度，只读显示','⌁'],
    ['status','车辆状态行','已核验状态的辅助显示，不替代原车告警','◇'],
    ['artwork','专辑封面','随曲目更换，可设置形状与透明度','▣'],
    ['track','曲目信息','标题、歌手、专辑，独立排版','≋'],
    ['progress','音乐进度','播放位置、总时长与进度动画','━'],
    ['lyrics','多行歌词','3 / 5 / 7 行，滚动、高亮与淡入淡出','≡'],
    ['navigation','导航信息','已提供的路名、距离、到达时间','↗'],
    ['clock','日期与时间','时间格式、日期格式与文字样式','◷'],
    ['text','文本与图标','自定义标题、内置图标、静态标注','Aa'],
    ['image','图片','主题本地素材，完整适配或填充','▧'],
    ['card','卡片与分组','圆角背景、透明度与子组件组合','□']
  ];
  const n = (id,type,x,y,w,h,extra={}) => ({id,type,x,y,w,h,...extra});
  const builtins = [
    {id:'simple',name:'简境 · Simple Drive',builtin:true,mode:'day',background:'slate',nodes:[n('map','map',540,35,900,462),n('speed-card','card',40,35,460,462),n('speed','value',70,100,400,250,{binding:'vehicle.displaySpeed'}),n('gear','text',170,365,190,75,{label:'D'}),n('music-card','card',1480,35,400,462),n('track','track',1520,60,320,125),n('lyrics','lyrics',1510,205,340,160),n('progress','progress',1520,390,320,80)]},
    {id:'neon',name:'霓序 · Neon Stack',builtin:true,mode:'night',background:'ink',nodes:[n('map','map',550,0,1370,532),n('speed-card','card',35,30,475,472),n('ring','ring',75,50,395,360),n('speed','value',135,130,280,210,{binding:'vehicle.displaySpeed'}),n('track','track',80,400,380,80),n('music-card','card',1420,320,460,180),n('lyrics','lyrics',1450,350,400,120)]},
    {id:'classic',name:'双表 · 经典仪表',builtin:true,mode:'night',background:'ink',nodes:[n('map','map',650,20,900,492),n('gauge','gauge',25,30,580,460),n('speed','value',140,180,340,180,{binding:'vehicle.displaySpeed'}),n('energy','ring',1580,60,290,290),n('track','track',1560,380,320,100)]}
  ];
  let saved;
  try { saved=JSON.parse(localStorage.getItem('autocluster-design-v1')||'null'); } catch { saved=null; }
  let themes = [...clone(builtins), ...(Array.isArray(saved?.themes)?saved.themes.filter(validTheme):[])];
  let active = themes.some(t=>t.id===saved?.active)?saved.active:'simple';
  let draft = clone(themes.find(t=>t.id===active));
  let selected = draft.nodes.find(item=>item.type==='value')?.id || draft.nodes[0]?.id;
  let mode = draft.mode, dirty=false, undo=[],redo=[], drag=null, page='themes',backgroundImage=null;
  let displaySettings = {strategy:'content-aware',maxFps:'30',animationFps:'30',appearance:'auto'};
  const palette = () => mode==='day'?{ink:'#172d3b',soft:'#5b6e7c',panel:'#edf3f5f2',track:'#c7d3dc',mapbg:'#dce4df',color:'#1368fb'}:{ink:'#f1f7ff',soft:'#a4b4c6',panel:'#111e2bef',track:'#34485e',mapbg:'#182533',color:'#54aaff'};
  function validTheme(t) { return t && /^[\w-]{1,80}$/.test(t.id) && typeof t.name==='string' && t.name.length<=100 && Array.isArray(t.nodes) && t.nodes.length<=80 && t.nodes.every(item=>item && typeof item.id==='string' && catalog.some(c=>c[0]===item.type) && ['x','y','w','h'].every(k=>Number.isFinite(item[k])) && item.w>0 && item.h>0 && item.x>=0 && item.y>=0 && item.x+item.w<=1920 && item.y+item.h<=532) && new Set(t.nodes.map(item=>item.id)).size===t.nodes.length; }
  function persist(){try{localStorage.setItem('autocluster-design-v1',JSON.stringify({themes:themes.filter(t=>!t.builtin),active}));return true;}catch{toast('浏览器存储不可用，草稿保留在当前页面');return false;}}
  function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,3300);}
  function remember(){undo.push(clone(draft));if(undo.length>40)undo.shift();redo=[];}
  function changed(){dirty=true;renderEditor();}
  function swatchColor(item,p){return item.overrides?.[mode]?.color||item.color||p.color;}
  function mapArt(){return `<svg class="map-art" viewBox="0 0 900 480" preserveAspectRatio="xMidYMid meet" aria-hidden="true"><g fill="none" stroke="var(--track)" stroke-width="4"><path d="M-50 60L950 290M-50 200L950 430M60 -50L-30 530M240 -50L150 530M460 -50L370 530M660 -50L570 530M860 -50L770 530"/><path d="M-50 350L930 120M300 -30L870 520" stroke-width="8"/></g><path d="M440 520L440 325Q440 255 500 245L680 203L820 170" fill="none" stroke="var(--color)" stroke-width="17" stroke-linecap="round"/><path d="M440 355L421 391L440 382L459 391Z" fill="var(--ink)" stroke="var(--color)" stroke-width="5"/><g fill="var(--soft)" font-size="20" font-family="sans-serif"><text x="590" y="290" transform="rotate(-13 590 290)">示意道路</text><text x="210" y="420">完整地图窗口</text></g></svg><div class="map-label">↗ &nbsp; 2.1 km &nbsp; 向右前方行驶</div>`;}
  function content(item){switch(item.type){
    case 'map':return mapArt();
    case 'value':return `<div class="value">${item.binding==='vehicle.soc'?'78':'68'}</div><div class="unit">${item.binding==='vehicle.soc'?'%':'km/h'}</div>`;
    case 'gauge':return `<svg class="gauge-art" viewBox="0 0 500 440" aria-hidden="true"><path d="M100 350 A200 200 0 1 1 400 350" fill="none" stroke="var(--track)" stroke-width="8"/><g fill="var(--soft)" font-size="24" font-family="sans-serif"><text x="60" y="280">0</text><text x="60" y="130">60</text><text x="210" y="50">120</text><text x="390" y="140">180</text><text x="410" y="300">240</text></g><path d="M250 240L145 94" stroke="var(--color)" stroke-width="8" stroke-linecap="round"/><circle cx="250" cy="240" r="12" fill="var(--ink)"/></svg>`;
    case 'ring':return `<svg class="gauge-art" viewBox="0 0 300 300" aria-hidden="true"><path d="M55 245 A135 135 0 1 1 245 245" fill="none" stroke="var(--track)" stroke-width="10" stroke-linecap="round"/><path d="M55 245 A135 135 0 0 1 165 16" fill="none" stroke="var(--color)" stroke-width="10" stroke-linecap="round"/></svg>`;
    case 'track':return `<strong>向着远方</strong><small>本地演示 · 音乐信息</small>`;
    case 'lyrics':return `<span>沿途的风景</span><b>在此刻慢慢展开</b><span>下一站，新的期待</span>`;
    case 'progress':return `<div class="progress-fill"></div><div class="time-row"><span>1:42</span><span>3:56</span></div>`;
    case 'artwork':return `<span style="font-size:25cqw">◒</span><small>ON THE WAY</small>`;
    case 'tires':return '<span style="font-size:14cqw">238　240<br>239　241</span>';
    case 'pedals':return '<span style="font-size:10cqw">油门 18%　刹车 0%</span>';
    case 'navigation':return '<span style="font-size:9cqw">剩余 18 km · 14:32 到达</span>';
    case 'status':return '<span style="font-size:10cqw">D　EV　78%</span>';
    case 'clock':return '<span style="font-size:23cqw">14:08</span>';
    case 'text':return `<span style="font-size:24cqw">${escape(item.label||'自定义文字')}</span>`;
    case 'image':return '<span style="font-size:12cqw">图片素材</span>';
    default:return '';
  }}
  function renderScene(container,theme,editing=false,forceMode=null){
    const old=mode;if(forceMode)mode=forceMode;const p=palette();
    const vars=Object.entries(p).map(([k,v])=>`--${k}:${v}`).join(';');
    const background=theme.background==='ink'?'#0b1520':mode==='night'?'#111b28':theme.background==='mist'?'#c6d5df':theme.background==='image'?'#233749':'#d3dfe8';
    container.innerHTML=`<div class="scene" style="${vars};background:${background}">${theme.nodes.map(item=>`<div data-id="${escape(item.id)}" class="node ${item.type==='map'?'map-node':item.type} ${['value','clock'].includes(item.type)?'vertical':''} ${editing&&selected===item.id?'selected-node':''}" style="left:${item.x/19.2}%;top:${item.y/5.32}%;width:${item.w/19.2}%;height:${item.h/5.32}%;--color:${swatchColor(item,p)};${item.overrides?.[mode]?.color?'--ink:'+swatchColor(item,p)+';':''}opacity:${item.opacity??1}">${content(item)}${editing&&selected===item.id?'<span class="resize-handle" data-resize="true" aria-hidden="true"></span>':''}</div>`).join('')}</div>`;
    if(theme.background==='image'&&theme.image){const scene=container.firstElementChild;scene.style.backgroundImage=`url("${theme.image}")`;scene.style.backgroundSize='cover';scene.style.backgroundPosition='center';}
    mode=old;
  }
  function showPage(next){page=next;for(const name of ['themes','editor','components']){$(`${name}-page`).hidden=name!==next;document.querySelector(`[data-page="${name}"]`).setAttribute('aria-selected',name===next);}if(next==='editor')renderEditor();if(next==='themes')renderLibrary();}
  function renderLibrary(){const current=themes.find(t=>t.id===active)||themes[0];$('active-name').textContent=current.name;renderScene($('active-preview'),current,false,current.mode);$('theme-count').textContent=`${themes.length} 套主题`;$('theme-grid').innerHTML='';themes.forEach(theme=>{const tile=document.createElement('article');tile.className='theme-tile';tile.innerHTML=`<div class="instrument"></div><div class="tile-caption"><div><h3>${escape(theme.name)}</h3><small>${theme.builtin?'内置模板 · 编辑生成副本':'自定义主题 · 本机保存'}</small></div>${theme.id===active?'<span class="active-badge">使用中</span>':''}</div><div class="tile-actions"><button data-edit="${escape(theme.id)}">编辑</button><button data-use="${escape(theme.id)}">${theme.id===active?'已应用':'切换'}</button></div>`;renderScene(tile.querySelector('.instrument'),theme,false,theme.mode);$('theme-grid').append(tile);});}
  function edit(theme){if(dirty){showLeave(()=>startEdit(theme));return;}startEdit(theme);}
  function startEdit(theme){draft=clone(theme);mode=draft.mode;selected=draft.nodes.find(item=>item.type==='value')?.id||draft.nodes[0]?.id;dirty=false;undo=[];redo=[];showPage('editor');}
  function renderEditor(){
    $('editing-name').textContent=draft.name;$('dirty-label').textContent=dirty?'有未保存的修改':draft.builtin?'内置模板，保存为自定义副本':'已保存';$('undo').disabled=undo.length===0;$('redo').disabled=redo.length===0;
    document.querySelectorAll('[data-mode]').forEach(button=>button.classList.toggle('selected',button.dataset.mode===mode));renderScene($('edit-preview'),draft,true);
    const item=draft.nodes.find(item=>item.id===selected);if(!item){$('inspector').innerHTML='<div class="empty"><h3>从组件库开始</h3><p>添加地图、速度或音乐组件</p></div>';return;}
    const title=catalog.find(c=>c[0]===item.type)?.[1];
    const field=(key,label)=>`<label class="field">${label}<input data-prop="${key}" type="number" value="${item[key]}" step="1" min="${key==='w'||key==='h'?1:0}"></label>`;
    $('inspector').innerHTML=`<div class="section-head"><h3>${title}</h3><div class="actions"><button id="duplicate-node">复制</button><button id="delete-node">删除</button></div></div><div class="property-grid">${field('x','X 位置')}${field('y','Y 位置')}${field('w','宽度')}${field('h','高度')}</div><div class="property-section"><div class="style-title"><h3>${mode==='day'?'日间':'夜间'} · 此组件样式</h3><small>未覆盖的属性继承主题</small></div><div class="swatches">${['#1368fb','#54aaff','#40c79a','#eead4e','#f1f7ff','#172d3b'].map(c=>`<button class="swatch" style="--swatch:${c}" data-color="${c}" aria-label="组件颜色 ${c}" aria-pressed="${swatchColor(item,palette())===c}"></button>`).join('')}<button id="inherit-style" style="margin-left:auto;min-height:44px;font-size:15px;padding:10px 16px">恢复继承</button></div></div><div class="property-section property-grid"><label class="field wide">数据来源<select id="binding"><option value="vehicle.displaySpeed">显示车速 · km/h</option><option value="vehicle.soc">动力电池电量 · %</option></select></label><label class="field wide">透明度<input id="opacity" type="range" min="0.1" max="1" step="0.05" value="${item.opacity??1}"></label></div><div class="binding-note">${item.type==='map'?'地图使用完整画面适配；正式版由 AutoService 合成，换主题保持视频会话。':item.type==='gauge'?'启动扫表只作用于装饰指针；有效车速非零时由实时值接管。':'当前为演示读数。正式投屏按数据状态与新鲜度显示，无有效值时显示 --。'}</div>`;
    $('binding').value=item.binding||'vehicle.displaySpeed';$('binding').disabled=item.type!=='value';
    $('duplicate-node').onclick=()=>{remember();const copy=clone(item);copy.id=`node-${Date.now()}`;copy.x=Math.min(1920-copy.w,copy.x+24);draft.nodes.push(copy);selected=copy.id;changed();};
    $('delete-node').onclick=()=>{remember();draft.nodes=draft.nodes.filter(n=>n.id!==selected);selected=draft.nodes.at(-1)?.id;changed();};
    $('inspector').querySelectorAll('[data-prop]').forEach(input=>input.onchange=()=>{const value=Number(input.value);if(!Number.isFinite(value))return;remember();item[input.dataset.prop]=Math.round(value);constrain(item);changed();});
    $('inspector').querySelectorAll('[data-color]').forEach(button=>button.onclick=()=>{remember();item.overrides??={};item.overrides[mode]={color:button.dataset.color};changed();});
    $('inherit-style').onclick=()=>{remember();if(item.overrides)delete item.overrides[mode];changed();};
    $('binding').onchange=e=>{remember();item.binding=e.target.value;changed();};$('opacity').onchange=e=>{remember();item.opacity=Number(e.target.value);changed();};
  }
  function constrain(item){item.w=Math.max(40,Math.min(1920,item.w));item.h=Math.max(25,Math.min(532,item.h));item.x=Math.max(0,Math.min(1920-item.w,item.x));item.y=Math.max(0,Math.min(532-item.h,item.y));}
  function sheet(title,html){$('sheet-title').textContent=title;$('sheet-body').innerHTML=html;$('scrim').hidden=false;}
  function closeSheet(){$('scrim').hidden=true;}
  function showLeave(proceed){sheet('保留当前修改？','<p>编辑中的主题尚未保存。</p><div class="actions" style="margin-top:24px"><button id="leave-save" class="primary">保存并继续</button><button id="leave-discard">放弃修改</button><button id="leave-cancel">继续编辑</button></div>');$('leave-save').onclick=()=>{if(save()){closeSheet();proceed();}};$('leave-discard').onclick=()=>{dirty=false;closeSheet();proceed();};$('leave-cancel').onclick=closeSheet;}
  function save(){draft.mode=mode;if(draft.builtin){draft.id=`theme-${Date.now()}`;draft.name=draft.name+' · 自定义';draft.builtin=false;}const index=themes.findIndex(t=>t.id===draft.id);if(index<0)themes.push(clone(draft));else themes[index]=clone(draft);if(!persist())return false;dirty=false;renderEditor();toast('已保存至本机原型主题库');return true;}
  function add(type){if(type==='map'&&draft.nodes.some(n=>n.type==='map')){toast('当前已有地图，请编辑现有地图窗口');closeSheet();return;}remember();const item=n(`node-${Date.now()}`,type,740,95,type==='map'?800:340,type==='map'?360:250);draft.nodes.push(item);selected=item.id;changed();closeSheet();showPage('editor');}
  function catalogHtml(adding=false){return catalog.map(c=>`<article class="catalog-item"><span class="catalog-icon">${c[3]}</span><h3>${c[1]}</h3><p>${c[2]}</p>${adding?`<button data-add="${c[0]}">添加到画布</button>`:""}</article>`).join('');}
  function addSheet(){sheet('添加组件',`<div class="catalog">${catalogHtml(true)}</div>`);}
  function backgroundSheet(){sheet('更换背景',`<p>日夜可分别设置纯色、渐变或本地图片。此处为背景交互示例。</p><div class="background-choices" style="margin-top:24px"><button data-bg="slate" class="background-choice" style="background:#658097">雾蓝</button><button data-bg="ink" class="background-choice" style="background:#101d2c">深海</button><button data-bg="mist" class="background-choice" style="background:linear-gradient(135deg,#a7b7c6,#dce4e9)">薄雾</button></div><label class="field" style="margin-top:24px">本地图片<input type="file" id="image-file" accept="image/png,image/jpeg,image/webp"></label>`);$('sheet-body').querySelectorAll('[data-bg]').forEach(button=>button.onclick=()=>{remember();draft.background=button.dataset.bg;delete draft.image;changed();closeSheet();});$('image-file').onchange=e=>{const file=e.target.files[0];if(!file)return;if(file.size>2*1024*1024){toast('原型图片限 2 MiB');return;}const reader=new FileReader();reader.onload=()=>{remember();draft.background='image';draft.image=reader.result;changed();closeSheet();};reader.readAsDataURL(file);};}
  function displaySheet(){sheet('显示设置',`<div class="sheet-row"><div>刷新策略<p>按内容变化绘制，静止时保持会话</p></div><select id="policy"><option value="content-aware">动态刷新</option><option value="fixed-cap">帧率上限</option></select></div><div class="sheet-row"><div>输出帧率上限<p>设置上限，实际帧率以诊断为准</p></div><select id="fps"><option>15</option><option>20</option><option>24</option><option>30</option><option>45</option><option>60</option></select></div><div class="sheet-row"><div>日夜模式<p>跟随系统，或固定日间 / 夜间</p></div><select id="auto-mode"><option value="auto">跟随系统</option><option value="day">日间</option><option value="night">夜间</option></select></div><div class="quiet-note">这是配置界面提案，尚未改变投屏帧率。45 / 60 档是否开放由目标设备实测决定。</div><div class="actions" style="margin-top:24px"><button class="primary" id="save-display">保存设置</button></div>`);$('policy').value=displaySettings.strategy;$('fps').value=displaySettings.maxFps;$('auto-mode').value=displaySettings.appearance;$('save-display').onclick=()=>{displaySettings={...displaySettings,strategy:$('policy').value,maxFps:$('fps').value,appearance:$('auto-mode').value};closeSheet();toast('已记录界面选择，未控制真实仪表');};}
  document.querySelectorAll('[data-page]').forEach(button=>button.onclick=()=>showPage(button.dataset.page));
  document.querySelectorAll('[data-mode]').forEach(button=>button.onclick=()=>{mode=button.dataset.mode;renderEditor();});
  $('appearance').onclick=()=>{const night=$('app').classList.toggle('night');$('appearance').textContent=night?'日间':'夜间';};
  $('display-open').onclick=displaySheet;$('close-sheet').onclick=closeSheet;$('scrim').onclick=e=>{if(e.target===$('scrim'))closeSheet();};document.addEventListener('keydown',e=>{if(e.key==='Escape')closeSheet();});
  $('edit-active').onclick=()=>edit(themes.find(t=>t.id===active));$('new-theme').onclick=()=>edit({id:`theme-${Date.now()}`,name:'未命名主题',builtin:false,mode:'day',background:'slate',nodes:[]});
  $('theme-grid').onclick=e=>{const editButton=e.target.closest('[data-edit]'),use=e.target.closest('[data-use]');if(editButton)edit(themes.find(t=>t.id===editButton.dataset.edit));if(use){active=use.dataset.use;persist();renderLibrary();toast('已切换原型预览，未控制真实仪表');}};
  $('save-theme').onclick=save;$('apply-theme').onclick=()=>{if(save()){active=draft.id;persist();toast('已应用到原型预览，未控制真实仪表');}};
  $('undo').onclick=()=>{if(!undo.length)return;redo.push(clone(draft));draft=undo.pop();dirty=true;renderEditor();};$('redo').onclick=()=>{if(!redo.length)return;undo.push(clone(draft));draft=redo.pop();dirty=true;renderEditor();};
  $('show-guides').onclick=()=>{const on=$('edit-preview').classList.toggle('guides');$('show-guides').setAttribute('aria-pressed',on);toast(on?'条纹为原车保留区域示意，待实车标定':'已隐藏参考线');};
  $('add-component').onclick=addSheet;$('background-open').onclick=backgroundSheet;$('rename-theme').onclick=()=>{sheet('主题名称','<label class="field">名称<input id="theme-name" maxlength="60"></label><button id="name-save" class="primary" style="margin-top:24px">确定</button>');$('theme-name').value=draft.name;$('name-save').onclick=()=>{const value=$('theme-name').value.trim();if(!value)return;remember();draft.name=value;changed();closeSheet();};};
  $('layers-open').onclick=()=>{sheet('图层 · 从上到下',`<div>${[...draft.nodes].reverse().map(item=>`<div class="sheet-row"><span>${catalog.find(c=>c[0]===item.type)?.[1]}</span><div class="actions"><button data-select-layer="${escape(item.id)}">选中</button><button data-top-layer="${escape(item.id)}">置顶</button></div></div>`).join('')}</div>`);};
  $('sheet-body').addEventListener('click',e=>{const addButton=e.target.closest('[data-add]'),layer=e.target.closest('[data-select-layer]'),top=e.target.closest('[data-top-layer]');if(addButton)add(addButton.dataset.add);if(layer){selected=layer.dataset.selectLayer;renderEditor();closeSheet();}if(top){remember();const i=draft.nodes.findIndex(item=>item.id===top.dataset.topLayer);draft.nodes.push(draft.nodes.splice(i,1)[0]);changed();closeSheet();}});
  $('catalog').innerHTML=catalogHtml();
  fetch('catalog/catalog.json').then(response=>response.ok?response.json():null).then(styles=>{
    if(!styles)return;
    $('catalog').innerHTML=styles.map(style=>`<article class="catalog-item"><img class="component-thumbnail" src="catalog/${escape(style.night)}" alt="${escape(style.title)} 实际组件预览"><h3>${escape(style.title)}</h3><p>样式预览 · 不会添加到仪表</p></article>`).join('');
  }).catch(()=>{});
  $('import-theme').onclick=()=>{sheet('导入主题包','<p>正式 App 使用包含 XML 和图片资源的主题包。本交互原型仅支持预览 JSON，不会安装或执行主题。</p><button class="primary" id="choose-json" style="margin-top:24px">选择预览 JSON</button>');$('choose-json').onclick=()=>$('package-file').click();};$('package-file').onchange=async e=>{const file=e.target.files[0];if(!file)return;if(file.size>3*1024*1024){toast('文件过大');return;}try{const theme=JSON.parse(await file.text());if(!validTheme(theme))throw Error();theme.builtin=false;theme.id=`theme-${Date.now()}`;themes.push(theme);persist();closeSheet();renderLibrary();toast('已导入原型预览');}catch{toast('预览文件无效，主题库保持不变');}e.target.value='';};
  $('edit-preview').addEventListener('pointerdown',e=>{const node=e.target.closest('[data-id]');if(!node)return;const item=draft.nodes.find(n=>n.id===node.dataset.id);selected=item.id;drag={id:item.id,startX:e.clientX,startY:e.clientY,original:clone(item),before:clone(draft),resize:!!e.target.closest('[data-resize]'),moved:false};renderEditor();e.preventDefault();});
  window.addEventListener('pointermove',e=>{if(!drag)return;const item=draft.nodes.find(n=>n.id===drag.id);const rect=$('edit-preview').getBoundingClientRect();const dx=(e.clientX-drag.startX)*1920/rect.width,dy=(e.clientY-drag.startY)*532/rect.height;if(Math.abs(dx)+Math.abs(dy)<2)return;drag.moved=true;if(drag.resize){item.w=Math.round(drag.original.w+dx);item.h=Math.round(drag.original.h+dy);}else{item.x=Math.round((drag.original.x+dx)/4)*4;item.y=Math.round((drag.original.y+dy)/4)*4;}constrain(item);const el=Array.from($('edit-preview').querySelectorAll('[data-id]')).find(n=>n.dataset.id===item.id);Object.assign(el.style,{left:item.x/19.2+'%',top:item.y/5.32+'%',width:item.w/19.2+'%',height:item.h/5.32+'%'});});
  const finishDrag=()=>{if(!drag)return;if(drag.moved){undo.push(drag.before);if(undo.length>40)undo.shift();redo=[];dirty=true;}drag=null;renderEditor();};window.addEventListener('pointerup',finishDrag);window.addEventListener('pointercancel',finishDrag);
  window.addEventListener('beforeunload',e=>{if(dirty){e.preventDefault();e.returnValue='';}});
  renderLibrary();renderEditor();
})();
