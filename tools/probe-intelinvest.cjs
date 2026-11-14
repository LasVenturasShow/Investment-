const {chromium}=require('playwright'),fs=require('fs');
(async()=>{
 const b=await chromium.launch({headless:true,args:['--no-sandbox']});const results=[];
 for(const target of ['https://intelinvest.ru/public/portfolio/781662/trades','https://intelinvest.ru/public/portfolio/781662','https://intelinvest.ru/public/portfolio/781662/assets']){
  const ctx=await b.newContext({locale:'ru-RU'}),p=await ctx.newPage(),r={target,requests:[],responses:[]};
  p.on('request',q=>{if(q.url().startsWith('https://intelinvest.ru/api/public/'))r.requests.push({method:q.method(),url:q.url(),post:q.postData()?.slice(0,800)})});
  p.on('response',async q=>{if(!q.url().startsWith('https://intelinvest.ru/api/public/'))return;const o={url:q.url(),status:q.status()};try{const d=await q.json();o.keys=Object.keys(d); for(const [k,v] of Object.entries(d)){
   if(Array.isArray(v))o[k]={count:v.length,first:v[0],last:v.at(-1)};else if(v&&typeof v==='object')o[k]={keys:Object.keys(v),first:v.content?.[0],totalElements:v.totalElements};else o[k]=v;
  }
  const s=JSON.stringify(o);if(s.length>30000){o.preview=s.slice(0,6000);for(const k of o.keys){if(typeof o[k]==='object')delete o[k]}}
  }catch(e){o.error=String(e)} r.responses.push(o)});
  try{r.navigation=(await p.goto(target,{waitUntil:'domcontentloaded',timeout:35000}))?.status();await p.waitForTimeout(9000);r.final=p.url();r.dom=await p.evaluate(()=>({body:document.body.innerText.slice(0,1800),tables:Array.from(document.querySelectorAll('table')).map(x=>({headers:Array.from(x.querySelectorAll('th')).map(v=>v.innerText),rows:x.querySelectorAll('tbody tr').length})).slice(0,8)}));}catch(e){r.error=String(e)}
  results.push(r);await ctx.close();
 }
 await b.close();fs.mkdirSync('diagnostics',{recursive:true});fs.writeFileSync('diagnostics/intelinvest-public-probe.json',JSON.stringify({results},null,2));console.log(results.map(r=>({target:r.target,status:r.navigation,urls:r.requests.map(x=>x.url)})));
})().catch(e=>{console.error(e);process.exitCode=1});