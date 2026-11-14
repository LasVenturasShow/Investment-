/* Mocked DOM contract smoke tests only. NOT a claim of successful IntelInvest scraping. */
const fs = require('fs');
const vm = require('vm');
const src = fs.readFileSync('app/src/main/assets/collector.js','utf8');
function node(text, id) {
  return {innerText:text, textContent:text, getAttribute:(name)=>name==='data-row-key'?id:null,
    querySelectorAll:()=>[]};
}
function run(extended) {
  const table = {
    getAttribute:()=>null,
    querySelector:()=>null,
    closest:()=>null,
    querySelectorAll:(sel)=>sel.includes('thead th')?
      [node('Дата'),node('Актив'),node('Тип операции'),node('Цена')]:
      [ {getAttribute:(name)=>name==='data-row-key'?'deal-alpha':null,
        querySelectorAll:()=>[node('10.11.2026'),node('SBER'),node('Покупка'),node('')]} ]
  };
  const next = {disabled:false, getAttribute:()=>null, classList:{contains:()=>false}};
  const document = {
    body:{innerText:'Дата Актив Тип операции Цена'}, title:'Mocked page',
    querySelectorAll:(s)=>s.startsWith('table,')?[table]:[],
    querySelector:(s)=>s.startsWith('.ant-pagination-next')&&extended?next:null
  };
  const result=vm.runInNewContext(src,{document,location:{href:'https://intelinvest.ru/public/portfolio/781662/trades'}});
  return JSON.parse(result);
}
const one=run(false);
if (one.tables.length!==1||one.tables[0].rows[0].cells.length!==4||one.hasNext) throw Error('table mapping failed');
if (!run(true).hasNext) throw Error('pagination detection failed');
console.log('PASS: JS extractor schema / empty-cell positions / pagination (mocked DOM)');
