#!/usr/bin/env node
// 本机浏览器生成四路测试图，经自有TURN强制中继；不是车辆首帧验收。
// 先在本机启动无数据的HTTP页面供WebRTC安全上下文使用，默认127.0.0.1:38117；可用GALAXY_TURN_PROBE_PAGE指定。
// PLAYWRIGHT_MODULE=/absolute/path/to/playwright node scripts/verify_turn_webrtc.cjs PRIVATE_ICE_JSON
const fs=require('node:fs');const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const ice=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
 const browser=await chromium.launch({headless:true,args:['--no-proxy-server','--force-webrtc-ip-handling-policy=default']});
 try{
 const page=await browser.newPage();await page.goto(process.env.GALAXY_TURN_PROBE_PAGE||'http://127.0.0.1:38117');
 const result=await page.evaluate(async ice=>{
  const a=new RTCPeerConnection({iceServers:[ice],iceTransportPolicy:'relay'}),b=new RTCPeerConnection({iceServers:[ice],iceTransportPolicy:'relay'});
  const pendingA=[],pendingB=[],videos=[],tracks=[],intervals=[];let errors=[];
  a.onicecandidate=e=>{if(e.candidate){if(b.remoteDescription)b.addIceCandidate(e.candidate).catch(()=>{});else pendingB.push(e.candidate)}};
  b.onicecandidate=e=>{if(e.candidate){if(a.remoteDescription)a.addIceCandidate(e.candidate).catch(()=>{});else pendingA.push(e.candidate)}};
  a.onicecandidateerror=e=>errors.push(e.errorCode);b.onicecandidateerror=e=>errors.push(e.errorCode);
  b.ontrack=e=>{const v=document.createElement('video');v.muted=true;v.autoplay=true;v.playsInline=true;v.srcObject=new MediaStream([e.track]);document.body.append(v);v.play().catch(()=>{});videos.push(v)};
  for(let n=0;n<4;n++){
   const canvas=document.createElement('canvas');canvas.width=640;canvas.height=400;
   const ctx=canvas.getContext('2d');const draw=()=>{ctx.fillStyle=['red','green','blue','purple'][n];ctx.fillRect(0,0,640,400);ctx.fillStyle='white';ctx.font='32px sans-serif';ctx.fillText('隔离测试 '+n+' '+Date.now(),20,80)};draw();intervals.push(setInterval(draw,70));
   const stream=canvas.captureStream(15);const track=stream.getVideoTracks()[0];tracks.push(track);const sender=a.addTrack(track,stream);
   const transceiver=a.getTransceivers().find(t=>t.sender===sender);transceiver.setCodecPreferences(RTCRtpSender.getCapabilities('video').codecs.filter(c=>c.mimeType.toLowerCase()==='video/h264'));
  }
  await a.setLocalDescription(await a.createOffer());await b.setRemoteDescription(a.localDescription);for(const c of pendingB)await b.addIceCandidate(c);
  await b.setLocalDescription(await b.createAnswer());await a.setRemoteDescription(b.localDescription);for(const c of pendingA)await a.addIceCandidate(c);
  let received=[];
  for(let i=0;i<30;i++){
   await new Promise(r=>setTimeout(r,1000));received=[];
   (await b.getStats()).forEach(s=>{if(s.type==='inbound-rtp'&&s.kind==='video')received.push({frames:s.framesDecoded||0,width:s.frameWidth,height:s.frameHeight,bytes:s.bytesReceived})});
   if(received.length===4&&received.every(s=>s.frames>=15))break;
  }
  const codecs=[];(await b.getStats()).forEach(s=>{if(s.type==='codec'&&s.mimeType==='video/H264')codecs.push(s.mimeType)});
  const result={connected:a.connectionState==='connected'&&b.connectionState==='connected',received,videoElements:videos.length,h264:codecs.length>0,errors};
  intervals.forEach(clearInterval);tracks.forEach(t=>t.stop());a.close();b.close();videos.forEach(v=>v.remove());return result;
 },ice);console.log(JSON.stringify(result));if(!result.connected||result.received.length!==4||!result.received.every(s=>s.frames>=15)||!result.h264)process.exitCode=1;
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exitCode=1});
