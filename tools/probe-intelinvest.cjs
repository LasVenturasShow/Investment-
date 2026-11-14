const {chromium} = require('playwright');
const fs = require('fs');
const targets=[
 'https://intelinvest.ru/public/portfolio/781662/trades',
 'https://intelinvest.ru/public/portfolio/781662',
 'https://intelinvest.ru/public-portfolio/781662/'
];
(async()=>{
 const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
 const results=[];
 for(const target of targets){
  const context=await browser.newContext({locale:'ru-RU',viewport:{width:1400,height:1000},userAgent:'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'});
  const page=await context.newPage();
  const record={target,requests:[],responses:[],errors:[]};
  page.on('request',req=>{ const u=req.url(); if(/intelinvest\.ru/.test(u)&&record.requests.length<120) record.requests.push({method:req.method(),url:u.split('?')[0].slice(0,200),kind:req.resourceType()});});
  page.on('requestfailed',req=>{if(record.errors.length<30) record.errors.push({url:req.url().split('?')[0].slice(0,180),failure:req.failure()});});
  page.on('response', async res=>{
    const u=res.url();
    if(!/intelinvest\.ru/.test(u)) return;
    const ct=res.headers()['content-type']||'';
    if(record.responses.length>90) return;
    const item={url:u.split('?')[0].slice(0,200),status:res.status(),kind:res.request().resourceType(),contentType:ct.slice(0,50)};
    if(/json/.test(ct)&&/api|portfolio|trade|public|asset|position/i.test(u)){
      try {const body=await res.text();item.preview=body.slice(0,8000);}catch(e){item.readError=String(e).slice(0,200)}
    }
    record.responses.push(item);
  });
  try {
   const reply=await page.goto(target,{waitUntil:'domcontentloaded',timeout:35000});
   record.navigationStatus=reply?.status();
   await page.waitForTimeout(14000);
   record.finalUrl=page.url();
   record.title=await page.title();
   record.dom=await page.evaluate(()=>{
    const clean=s=>String(s||'').replace(/\s+/g,' ').trim();
    return {
      body:clean(document.body?.innerText).slice(0,11000),
      tables:Array.from(document.querySelectorAll('table,[role="table"],[role="grid"]')).slice(0,15).map(el=>({html:el.outerHTML.slice(0,2400),text:clean(el.innerText).slice(0,1800)})),
      grids:Array.from(document.querySelectorAll('[role="row"],[class*="row"],[class*="Row"]')).slice(0,15).map(el=>({tag:el.tagName,cls:String(el.className).slice(0,150),text:clean(el.innerText).slice(0,300)})),
      navLinks:Array.from(document.querySelectorAll('a[href]')).filter(x=>/portfolio|trades|сделк/i.test(x.href+' '+x.innerText)).slice(0,25).map(a=>({href:a.href.slice(0,180),text:clean(a.innerText).slice(0,90)})),
      scripts:Array.from(document.querySelectorAll('script[src]')).map(el=>el.src.slice(0,240)).slice(0,35),
      nextData:document.querySelector('#__NEXT_DATA__')?.textContent?.slice(0,3000)||'',
      appRoots:Array.from(document.querySelectorAll('#root,#app,#__next')).map(el=>({id:el.id,html:el.outerHTML.slice(0,600)}))
    };
   });
  } catch(e) {record.fatal=String(e);}
  results.push(record);await context.close();
 }
 await browser.close();
 fs.mkdirSync('diagnostics',{recursive:true});
 fs.writeFileSync('diagnostics/intelinvest-public-probe.json',JSON.stringify({generated:new Date().toISOString(),results},null,2));
 console.log(results.map(x=>({target:x.target,status:x.navigationStatus,final:x.finalUrl,bodyStart:x.dom?.body?.slice(0,200),responses:x.responses.length,fatal:x.fatal})));
})().catch(e=>{console.error(e);process.exitCode=1});
