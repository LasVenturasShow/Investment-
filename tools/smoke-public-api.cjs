const assert = require('node:assert/strict');
const ROOT = 'https://intelinvest.ru/api/public';
const ID = 781662;
async function fetchJson(url) {
  const abort = new AbortController(); const timer = setTimeout(() => abort.abort(), 20000);
  try {
    const response = await fetch(url, {signal: abort.signal, headers:{Accept:'application/json',Referer:'https://intelinvest.ru/public/portfolio/781662/trades','User-Agent':'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36'}});
    assert.equal(response.status, 200, `${url} HTTP ${response.status}`);
    assert.match(response.headers.get('content-type')||'', /json/i);
    return await response.json();
  } finally { clearTimeout(timer); }
}
(async()=>{
 const ids = new Set(), history = [], perPage = 25; let offset = 0, total = null;
 while(true) {
  const query = `offset=${offset}&limit=${perPage}&&&showLinkedMoneyTrades=false&showMoneyTrades=true&search=&&start=&end=&&&&calculateSubTotal=true&sortColumn=DATE&descending=true`;
  const page = await fetchJson(`${ROOT}/trades/${ID}?${query}`);
  // Server metadata pageNumber/offset stay zero on later pages (observed in real UI).
  assert.equal(page.pageSize, perPage);
  assert.ok(Number.isInteger(page.totalItems) && page.totalItems > 0);
  if(total === null) total = page.totalItems;
  assert.equal(page.totalItems, total);
  assert.ok(Array.isArray(page.content) && page.content.length > 0);
  for(const item of page.content) {
    assert.ok(Number.isInteger(item.id) && !ids.has(item.id), `Duplicate trade ${item.id}`);
    assert.equal(item.portfolioId, ID);
    assert.ok(item.operationLabel && item.date);
    ids.add(item.id); history.push(item);
  }
  assert.ok(ids.size <= total && ids.size < 20000);
  if(ids.size === total) break;
  assert.equal(page.content.length, perPage, `Short intermediate page at ${offset}`);
  offset += perPage;
 }
 const overview = await fetchJson(`${ROOT}/portfolios/${ID}/overview`);
 assert.ok(Array.isArray(overview.rows));
 const positions = overview.rows.filter(row => row.share?.ticker && Number(row.percCurrShareInWholePortfolio) > 0);
 const sum = positions.reduce((a,row)=>a+Number(row.percCurrShareInWholePortfolio),0);
 assert.ok(sum >= 98 && sum <= 102, `Allocation sum invalid: ${sum}`);
 assert.ok(positions.every(row => row.share?.name && row.quantity!==undefined));
 const parseRub = value => {
   const match = /^RUB\s+([+-]?[0-9]+(?:\.[0-9]+)?)$/.exec(String(value||'').trim());
   assert.ok(match, `Expected RUB amount, got ${value}`);
   return Number(match[1]);
 };
 const assetsTotal = positions.reduce((n,row) => n + parseRub(row.currCost),0);
 assert.ok(assetsTotal > 0, "Asset amounts must be positive");
 const totalFromApi = overview.sumRow?.currCost ? parseRub(overview.sumRow.currCost) : assetsTotal;
 assert.ok(Math.abs(assetsTotal-totalFromApi) <= Math.max(100, 0.02*totalFromApi),
   `Portfolio value mismatch: total=${totalFromApi}, assets=${assetsTotal}`);
 assert.ok(typeof positions[0].currCost === 'string');
 const gains = positions.filter(row => row.assetType !== 'MONEY').map(row => {
   const invested = parseRub(row.openPositionInvestedAmount);
   const current = parseRub(row.currCost);
   assert.ok(invested > 0 && current >= 0, `Invalid open position for ${row.share.ticker}`);
   const amount = current - invested;
   const pct = 100 * amount / invested;
   return {ticker:row.share.ticker, amount, pct};
 });
 assert.equal(gains.length, positions.filter(row => row.assetType !== 'MONEY').length);
 for(const gain of gains)assert.ok(Number.isFinite(gain.amount) && Number.isFinite(gain.pct));
 const mtss = gains.find(g=>g.ticker==='MTSS');
 if(mtss) console.log('MTSS: current open-position gain (RUB):', mtss.amount.toFixed(2),
   'percent:', mtss.pct.toFixed(2));
 console.log('Open position gains checked:', gains.length);
 // The identical normalized live input is independently checked by the Kotlin reconciler.
 const fs = require('node:fs');
 const rub = value => /^RUB\s+([+-]?[0-9]+(?:\.[0-9]+)?)$/.exec(String(value||'').trim())?.[1] || '';
 const cash = t => {
   const l=t.linkedTrade;
   if(!l || l.id!==t.moneyTradeId || l.parentTradeId!==t.id || l.portfolioId!==ID || l.asset!=='MONEY' || l.currency!=='RUB')return '';
   const amount=rub(l.signedTotal);
   return (t.operation==='SELL' && Number(amount)>0 || t.operation==='BUY' && Number(amount)<0) ? amount : '';
 };
 const meta=['M',overview.lastTradeDate,rub(overview.sumRow?.currCost)];
 const rows=overview.rows.map(r=>['P',r.share.id,r.share.ticker,r.assetType,r.quantity,rub(r.currCost),rub(r.currPrice),rub(r.openPositionInvestedAmount)]);
 const trades=history.map(t=>['T',t.id,t.shareId,t.ticker,t.asset,t.operation,t.date,t.quantity??'',cash(t)]);
 if(process.env.PORTFOLIO_FIXTURE)fs.writeFileSync(process.env.PORTFOLIO_FIXTURE,[meta,...rows,...trades].map(r=>r.map(x=>String(x??'').replace(/[\t\r\n]/g,' ')).join('\t')).join('\n')+'\n');
 console.log('Reconciliation fixture rows:',rows.length,'trades:',trades.length);
 console.log(JSON.stringify({result:'PASS',portfolio:ID,trades:ids.size,activePositions:positions.length,allocationPercent:sum.toFixed(2),valueRub:totalFromApi.toFixed(2),
   sampleHoldingRub:parseRub(positions[0].currCost).toFixed(2),names:positions.slice(0,3).map(r=>[r.share.ticker,r.share.name])},null,2));
})().catch(error=>{console.error('Public API smoke test FAILED:',error.message);process.exitCode=1});
