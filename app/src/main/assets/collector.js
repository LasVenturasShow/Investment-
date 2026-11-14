(function () {
  'use strict';
  const clean = (s) => String(s || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  function cells(tr) {
    const td = Array.from(tr.querySelectorAll(':scope > td, :scope > th, :scope > [role="cell"], :scope > [role="gridcell"]'));
    return td.map(e => clean(e.innerText || e.textContent));
  }
  function collectTable(el) {
    const trs = Array.from(el.querySelectorAll('tbody tr, [role="rowgroup"] [role="row"], :scope > [role="row"]'));
    const headers = Array.from(el.querySelectorAll('thead th, [role="columnheader"]')).map(e => clean(e.textContent)).filter(Boolean);
    const data = trs.slice(0, 1800).map(tr => ({
      id: tr.getAttribute('data-row-key') || tr.getAttribute('data-id') || '',
      cells: cells(tr)
    })).filter(r => r.cells.length >= 2 && r.cells.some(Boolean));
    const announced = Number(el.getAttribute("aria-rowcount") || (el.querySelector("[aria-rowcount]") || {}).getAttribute?.("aria-rowcount") || 0);
    return {headers, rows: data, visibleRows: trs.length, truncated: trs.length > 1800 || (announced > trs.length + 1)};
  }
  const tables = Array.from(document.querySelectorAll('table, [role="table"], [role="grid"]'))
    .filter(t => !t.closest('table table, [role="grid"] [role="grid"]'))
    .slice(0, 15).map(collectTable)
    .filter(t => t.headers.length || t.rows.length);
  const next = document.querySelector('.ant-pagination-next, button[aria-label="Go to next page"], button[aria-label="Next page"], [aria-label="Следующая страница"], .pagination-next');
  const hasNext = !!(next && !next.disabled && next.getAttribute('aria-disabled') !== 'true' && !next.classList.contains('ant-pagination-disabled') && !next.classList.contains('disabled'));
  const text = clean(document.body ? document.body.innerText : '');
  return JSON.stringify({
    url: String(location.href), title: String(document.title),
    tables, hasNext, loading: !!document.querySelector('[aria-busy="true"], .ant-spin-spinning, [role="progressbar"]'),
    textSample: text.slice(0, 350)
  });
})()
