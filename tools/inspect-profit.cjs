const fs=require('node:fs');
const url='https://intelinvest.ru/api/public/portfolios/781662/overview';
const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(),25000);
(async()=>{
 const resp=await fetch(url,{signal:controller.signal,headers:{Accept:'application/json',Referer:'https://intelinvest.ru/public/portfolio/781662','User-Agent':'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126 Safari/537.36'}});
 if(!resp.ok) throw Error('HTTP '+resp.status);
 const d=await resp.json(); if(!Array.isArray(d.rows))throw Error('missing rows');
 const numeric=x=>typeof x==='string'?Number(x.replace(/^RUB /,'').replace(',','.')):Number(x);
 const fields=['ticker','name','quantity','percCurrShareInWholePortfolio','currCost','currPrice','avgBuy','openPositionAvgPrice','openPositionAvgPriceClean','openPositionInvestedAmount','openPositionCost','openPositionQty','exchangeProfit','profit','percProfit','dailyPl','dailyPlPercent','rateProfit','rateProfitPercent','percShareOpenPositionInvestedAmount','openPositionFee'];
 const p=d.rows.filter(x=>x.share?.ticker && numeric(x.percCurrShareInWholePortfolio)>0).map(x=>Object.fromEntries(fields.map(k=>[k,k==='ticker'?x.share.ticker:k==='name'?x.share.name:x[k]]).concat([
  ['profitPercents',x.profitPercents],
  ['calc_current_minus_invested',numeric(x.currCost)-numeric(x.openPositionInvestedAmount)],
  ['calc_current_minus_open_cost',numeric(x.currCost)-numeric(x.openPositionCost)],
  ['price_minus_openAvg_times_qty',(numeric(x.currPrice)-numeric(x.openPositionAvgPrice))*numeric(x.openPositionQty)]
 ])));
 fs.mkdirSync('diagnostics',{recursive:true});
 fs.writeFileSync('diagnostics/active-positions-profit-fields.json',JSON.stringify({capturedAt:new Date().toISOString(),overviewCalculatedAt:d.calculatedAt,columns:p,overviewFields:{totalTradesCount:d.totalTradesCount,totalOpenPositionCount:d.totalOpenPositionCount,hasSumRow:!!d.sumRow}},null,2));
 console.log('Active positions:',p.length); for(const x of p)console.log(x.ticker,'curr',x.currCost,'profit',x.profit,'profitPercent',x.percProfit,'open cost',x.openPositionInvestedAmount);
})().catch(e=>{console.error(e);process.exitCode=1}).finally(()=>clearTimeout(timer));