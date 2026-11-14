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
 const ids = new Set(), perPage = 100; let pageIndex = 0, total = null;
 while(true) {
  const params = new URLSearchParams({offset: String(pageIndex),limit: String(perPage),showLinkedMoneyTrades:'false',showMoneyTrades:'true',calculateSubTotal:'true',sortColumn:'DATE',descending:'true'});
  const page = await fetchJson(`${ROOT}/trades/${ID}?${params}`);
  assert.equal(page.offset, pageIndex);
  assert.equal(page.pageNumber, pageIndex);
  assert.ok(Number.isInteger(page.totalItems) && page.totalItems > 0);
  if(total === null) total = page.totalItems;
  assert.equal(page.totalItems, total);
  assert.ok(Array.isArray(page.content) && page.content.length > 0);
  for(const item of page.content) {
    assert.ok(Number.isInteger(item.id) && !ids.has(item.id), `Duplicate trade ${item.id}`);
    assert.equal(item.portfolioId, ID);
    assert.ok(item.operationLabel && item.date);
    ids.add(item.id);
  }
  assert.ok(ids.size <= total && ids.size < 20000);
  if(ids.size === total) break;
  pageIndex++;
 }
 const overview = await fetchJson(`${ROOT}/portfolios/${ID}/overview`);
 assert.ok(Array.isArray(overview.rows));
 const positions = overview.rows.filter(row => row.share?.ticker && Number(row.percCurrShareInWholePortfolio) > 0);
 const sum = positions.reduce((a,row)=>a+Number(row.percCurrShareInWholePortfolio),0);
 assert.ok(sum >= 98 && sum <= 102, `Allocation sum invalid: ${sum}`);
 assert.ok(positions.every(row => row.share?.name && row.quantity!==undefined));
 console.log(JSON.stringify({result:'PASS',portfolio:ID,trades:ids.size,activePositions:positions.length,allocationPercent:sum.toFixed(2),names:positions.slice(0,3).map(r=>[r.share.ticker,r.share.name])},null,2));
})().catch(error=>{console.error('Public API smoke test FAILED:',error.message);process.exitCode=1});
