import puppeteer from 'puppeteer-core';
import fs from 'fs';
const mode=process.argv[2]||'stills';
const browser=await puppeteer.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:'new',args:['--allow-file-access-from-files']});
const page=await browser.newPage();
await page.setViewport({width:1440,height:1080,deviceScaleFactor:1});
await page.goto('file://'+process.cwd()+'/promo.html',{waitUntil:'load'});
await new Promise(r=>setTimeout(r,500));
if(mode==='stills'){
  for(const t of [1.8,5,9.5,14.5,19,23.5]){
    await page.evaluate(t=>render(t),t);
    await page.screenshot({path:`still_${t}.png`});
  }
}else{
  fs.mkdirSync('frames',{recursive:true});
  const dur=await page.evaluate(()=>window.DURATION),fps=30,n=Math.round(dur*fps);
  for(let i=0;i<n;i++){
    await page.evaluate(t=>render(t),i/fps);
    await page.screenshot({path:`frames/f${String(i).padStart(4,'0')}.png`});
  }
  console.log('frames',n);
}
await browser.close();
