// Render only the static design artifact; does not launch or modify the Android app.
const {chromium}=require(process.env.HUEVIA_DESIGN_PLAYWRIGHT_PATH || 'playwright');
const path=require('node:path');
const fs=require('node:fs/promises');
const {pathToFileURL}=require('node:url');
(async()=>{
  const browser=await chromium.launch({executablePath:process.env.HUEVIA_DESIGN_BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true,args:['--allow-file-access-from-files']});
  try{
    const page=await browser.newPage({viewport:{width:1458,height:1150},deviceScaleFactor:2});
    await page.goto(pathToFileURL(path.join(__dirname,'review.html')).href);
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));});
    const geometry=await page.evaluate(()=>{
      const take=s=>{const b=document.querySelector(s).getBoundingClientRect();return{x:b.x,y:b.y,width:b.width,height:b.height}};
      const relative=(s,p)=>{const a=take(s),b=take(p);return{x:a.x-b.x,y:a.y-b.y,width:a.width,height:a.height}};
      return {defaultPhoto:relative('#editor-default .image-fit','#editor-default'),histogramPhoto:relative('#editor-histogram .image-fit','#editor-histogram'),defaultDock:relative('#editor-default .dock','#editor-default'),histogramDock:relative('#editor-histogram .dock','#editor-histogram'),scope:'Static UI design geometry, not native runtime proof'};
    });
    if(JSON.stringify(geometry.defaultPhoto)!==JSON.stringify(geometry.histogramPhoto)||JSON.stringify(geometry.defaultDock)!==JSON.stringify(geometry.histogramDock))throw new Error('Design photo/dock shifts when histogram opens');
    await page.locator('#editor-board').screenshot({path:path.join(__dirname,'review-editor-v1.png')});
    await page.locator('#export-board').screenshot({path:path.join(__dirname,'review-export-v1.png')});
    await fs.writeFile(path.join(__dirname,'design-geometry.json'),JSON.stringify(geometry,null,2));
    console.log(JSON.stringify(geometry));
  }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
