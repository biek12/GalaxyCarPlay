import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'
import QRCode from 'qrcode'
import jsQR from 'jsqr'
const source=await readFile(new URL('../src/features/vehicles/bindingCode.ts',import.meta.url),'utf8')
const {parseBindingCode}=await import('data:text/javascript;base64,'+Buffer.from(ts.transpile(source,{module:ts.ModuleKind.ESNext})).toString('base64'))
globalThis.window={location:{origin:'https://example.test'}}
test('manual code and own-origin link',()=>{
 assert.equal(parseBindingCode('0123456789abcdef'),'0123456789abcdef')
 assert.equal(parseBindingCode('https://example.test/galaxy/app/vehicles#bind=0123456789abcdef'),'0123456789abcdef')
 assert.throws(()=>parseBindingCode('https://other.test/vehicles#bind=0123456789abcdef'))
 assert.throws(()=>parseBindingCode('javascript:alert(1)'))
 assert.throws(()=>parseBindingCode('123456'))
})
test('real QR pixel decoding returns original binding link',()=>{
 const link='https://example.test/galaxy/app/vehicles#bind=0123456789abcdef'
 const qr=QRCode.create(link,{errorCorrectionLevel:'M'});const scale=6,margin=4,size=(qr.modules.size+margin*2)*scale
 const pixels=new Uint8ClampedArray(size*size*4).fill(255)
 for(let y=0;y<size;y++)for(let x=0;x<size;x++){
  const qx=Math.floor(x/scale)-margin,qy=Math.floor(y/scale)-margin
  if(qx>=0&&qy>=0&&qx<qr.modules.size&&qy<qr.modules.size&&qr.modules.get(qy,qx)){const i=(y*size+x)*4;pixels[i]=pixels[i+1]=pixels[i+2]=0}
 }
 assert.equal(parseBindingCode(jsQR(pixels,size,size).data),'0123456789abcdef')
})
