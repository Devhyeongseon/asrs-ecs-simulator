import { useEffect, useMemo, useState } from 'react'

const ROWS = 5
const COLS = 10
const ACTIVE = ['WAITING', 'REQUESTED', 'RUNNING']

const STATUS_LABEL = {
  WAITING: '대기', REQUESTED: '요청', RUNNING: '동작중', DONE: '완료', ERROR: '에러', CANCELED: '취소'
}

async function api(path, options = {}) {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!res.ok) {
    let msg = `${res.status}`
    try { msg = (await res.json()).message || msg } catch { /* ignore */ }
    throw new Error(msg)
  }
  return res.json()
}

export default function App() {
  const [state, setState] = useState(null)
  const [error, setError] = useState('')
  const [toast, setToast] = useState('')
  const [uploadResult, setUploadResult] = useState(null)

  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const s = await api('/state')
        if (alive) { setState(s); setError('') }
      } catch {
        if (alive) setError('백엔드(localhost:8080)에 연결할 수 없습니다. Spring Boot를 먼저 실행하세요.')
      }
    }
    load()
    const t = setInterval(load, 700)
    return () => { alive = false; clearInterval(t) }
  }, [])

  const run = async (fn, okMsg) => {
    try {
      const r = await fn()
      if (r && r.received !== undefined) {
        setUploadResult(r)
        setToast(`스케줄 수신 ${r.received}건 · 등록 ${r.accepted}건 · 거부 ${r.rejected.length}건`)
      } else if (okMsg) {
        setToast(okMsg)
      }
    } catch (e) {
      setToast(`요청 거부: ${e.message}`)
    }
    setTimeout(() => setToast(''), 3000)
  }

  if (!state) {
    return <div className="loading">{error || '불러오는 중...'}</div>
  }

  return (
    <div className="app">
      <header>
        <div>
          <h1>AS/RS ECS Simulator</h1>
          <p>MES → WMS 작업목록 → ECS → 스태커크레인 PLC 핸드셰이크 시뮬레이션</p>
        </div>
        <Stats stats={state.stats} ecs={state.ecs} plc={state.plc} />
      </header>

      {error && <div className="banner">{error}</div>}
      {toast && <div className="toast">{toast}</div>}

      <div className="grid">
        <section className="card rack-card">
          <h2>랙 맵 <span className="hint">5행 × 10열 · 0열은 입출고 포트</span></h2>
          <RackMap state={state} />
        </section>

        <section className="card">
          <h2>PLC 메모리맵 <span className="hint">EdgeHub 드라이버 통신 가정</span></h2>
          <Handshake plc={state.plc} ecs={state.ecs} />
        </section>

        <section className="card">
          <h2>MES 스케줄 · 작업 지시 · 장애 주입</h2>
          <Controls state={state} run={run} uploadResult={uploadResult} />
        </section>

        <section className="card wide">
          <h2>WMS 작업 대기열 <span className="hint">서열 순으로 ECS가 가져감 · 대기 상태만 순서 변경·취소 가능</span></h2>
          <Queue queue={state.queue} paused={state.ecs.paused} run={run} />
        </section>

        <section className="card wide">
          <h2>진행 · 이력</h2>
          <Orders orders={state.orders.filter(o => o.status !== 'WAITING')} run={run} />
        </section>

        <section className="card wide">
          <h2>이벤트 로그</h2>
          <Events events={state.events} />
        </section>
      </div>
    </div>
  )
}

function Stats({ stats, ecs, plc }) {
  return (
    <div className="stats">
      <Stat label="재고" value={`${stats.stock} / ${stats.capacity}`} />
      <Stat label="대기 작업" value={stats.waiting} />
      <Stat label="완료" value={stats.done} />
      <Stat label="에러" value={stats.error} danger={stats.error > 0} />
      <Stat label="ECS" value={ecs.paused ? `${ecs.phase} · 정지` : ecs.phase} small danger={ecs.paused} />
      <Stat label="PLC" value={plc.phase} small />
    </div>
  )
}

function Stat({ label, value, danger, small }) {
  return (
    <div className={`stat ${danger ? 'danger' : ''}`}>
      <span>{label}</span>
      <b className={small ? 'small' : ''}>{value}</b>
    </div>
  )
}

function RackMap({ state }) {
  const { locations, orders, plc, ecs } = state
  const byId = useMemo(() => Object.fromEntries(locations.map(l => [l.id, l])), [locations])
  const activeTargets = useMemo(() => {
    const m = {}
    orders.filter(o => ACTIVE.includes(o.status)).forEach(o => { m[o.locationId] = o })
    return m
  }, [orders])
  const current = orders.find(o => o.id === ecs.currentOrderId)

  const rows = []
  for (let r = ROWS; r >= 1; r--) {
    const cells = []
    for (let c = 0; c <= COLS; c++) {
      const isCrane = plc.craneRow === r && plc.craneCol === c
      const craneCol = plc.craneCol === c
      if (c === 0) {
        cells.push(
          <div key={c} className={`cell port ${craneCol ? 'crane-col' : ''} ${isCrane ? 'crane' : ''}`}>
            {r === 1 ? 'P/D' : ''}
          </div>
        )
        continue
      }
      const id = `R${String(r).padStart(2, '0')}-C${String(c).padStart(2, '0')}`
      const loc = byId[id]
      const target = activeTargets[id]
      const isCurrent = current && current.locationId === id
      const cls = [
        'cell',
        loc?.status === 'OCCUPIED' ? 'occupied' : 'empty',
        target ? (target.type === 'INBOUND' ? 'reserved-in' : 'reserved-out') : '',
        isCurrent ? 'current' : '',
        craneCol ? 'crane-col' : '',
        isCrane ? 'crane' : ''
      ].join(' ')
      const title = loc?.status === 'OCCUPIED'
        ? `${id}\n${loc.palletNo}\n${loc.itemName}\n${loc.lotId}`
        : `${id}\n빈 랙`
      cells.push(
        <div key={c} className={cls} title={title}>
          {loc?.status === 'OCCUPIED' ? loc.palletNo.replace('PLT-', '') : ''}
        </div>
      )
    }
    rows.push(
      <div key={r} className="rack-row">
        <div className="row-label">{r}행</div>
        {cells}
      </div>
    )
  }

  return (
    <div className="rack">
      {rows}
      <div className="rack-row">
        <div className="row-label" />
        {Array.from({ length: COLS + 1 }, (_, c) => <div key={c} className="col-label">{c}</div>)}
      </div>
      <div className="legend">
        <span><i className="sw occupied" />재고</span>
        <span><i className="sw empty" />빈 랙</span>
        <span><i className="sw reserved-in" />입고 예정</span>
        <span><i className="sw reserved-out" />출고 예정</span>
        <span><i className="sw crane" />크레인 위치</span>
      </div>
    </div>
  )
}

function Handshake({ plc, ecs }) {
  return (
    <div className="handshake">
      <MemTable title="ECS → PLC" rows={plc.ecsToPlc} />
      <MemTable title="PLC → ECS" rows={plc.plcToEcs} />
      <p className="hint block">
        ECS 상태 <b>{ecs.phase}</b>{ecs.currentOrderId ? ` · 작업 ID ${ecs.currentOrderId}` : ''} ·
        PLC 상태 <b>{plc.phase}</b>
      </p>
    </div>
  )
}

function MemTable({ title, rows }) {
  return (
    <div className="mem">
      <h3>{title}</h3>
      {rows.map(w => (
        <div key={w.addr} className="mem-row">
          <code>{w.addr}</code>
          <span className="mem-name">{w.name}</span>
          {w.bit
            ? <i className={`lamp ${w.value ? 'on' : ''}`} />
            : <b className="mem-val">{w.value}</b>}
        </div>
      ))}
    </div>
  )
}

function parseCsv(text) {
  // 형식: type,palletNo,itemName,lotId  (type: IN/OUT 또는 입고/출고, 첫 줄이 헤더면 건너뜀)
  return text.split(/\r?\n/)
    .map(l => l.trim())
    .filter(l => l.length > 0)
    .filter((l, i) => !(i === 0 && /^type\s*,/i.test(l)))
    .map(l => {
      const [type, palletNo, itemName, lotId] = l.split(',').map(v => (v ?? '').trim())
      return { type, palletNo: palletNo || null, itemName: itemName || null, lotId: lotId || null }
    })
}

function Controls({ state, run, uploadResult }) {
  const [pallet, setPallet] = useState('')
  const [mode, setMode] = useState('append')
  const busyPallets = new Set(state.orders.filter(o => ACTIVE.includes(o.status)).map(o => o.palletNo))
  const stock = state.locations.filter(l => l.status === 'OCCUPIED')
  const f = state.faults
  const paused = state.ecs.paused

  const setFault = (key) => run(
    () => api('/faults', { method: 'POST', body: JSON.stringify({ [key]: !f[key] }) }),
    null
  )

  const onFile = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    const items = parseCsv(await file.text())
    e.target.value = ''
    run(() => api('/mes/schedule', { method: 'POST', body: JSON.stringify({ mode, items }) }))
  }

  const outbound = (urgent) => run(
    () => api('/orders/outbound', { method: 'POST', body: JSON.stringify({ palletNo: pallet, urgent }) }),
    urgent ? '긴급 출고 삽입' : '출고 작업 생성'
  )

  return (
    <div className="controls">
      <Toggle on={paused} onClick={() => run(() => api('/ecs/pause', { method: 'POST', body: JSON.stringify({ paused: !paused }) }), null)}
        title="ECS 작업 투입 일시정지" desc="새 작업을 설비에 내리지 않음 (진행 중 작업은 끝까지 처리) → 대기열 편집용" />

      <h3>MES 작업 스케줄 수신</h3>
      <div className="seg">
        <label className={mode === 'append' ? 'on' : ''}><input type="radio" checked={mode === 'append'} onChange={() => setMode('append')} />뒤에 추가</label>
        <label className={mode === 'replace' ? 'on' : ''}><input type="radio" checked={mode === 'replace'} onChange={() => setMode('replace')} />전체 교체</label>
      </div>
      <div className="btn-row">
        <button onClick={() => run(() => api(`/mes/schedule/sample?count=10&mode=${mode}`, { method: 'POST' }))}>
          샘플 10건 수신
        </button>
        <label className="file-btn">
          CSV 업로드
          <input type="file" accept=".csv,text/csv" onChange={onFile} />
        </label>
      </div>
      {mode === 'replace' && <p className="hint block warn">전체 교체 시 기존 대기 작업은 모두 취소됩니다 (진행 중 작업은 유지).</p>}
      {uploadResult && uploadResult.rejected.length > 0 && (
        <div className="rejects">
          <b>거부된 행</b>
          {uploadResult.rejected.map(r => (
            <div key={r.line}>{r.line}행 {r.palletNo ?? ''} · {r.reason}</div>
          ))}
        </div>
      )}

      <h3>단건 · 긴급 작업</h3>
      <div className="btn-row">
        <button onClick={() => run(() => api('/orders/inbound', { method: 'POST', body: '{}' }), '입고 작업 생성')}>입고</button>
        <button className="urgent" onClick={() => run(() => api('/orders/inbound', { method: 'POST', body: JSON.stringify({ urgent: true }) }), '긴급 입고 삽입')}>긴급 입고</button>
      </div>
      <div className="btn-row">
        <select value={pallet} onChange={e => setPallet(e.target.value)}>
          <option value="">출고할 팔레트 선택</option>
          {stock.map(l => (
            <option key={l.id} value={l.palletNo}>
              {l.palletNo} · {l.id} {busyPallets.has(l.palletNo) ? '(진행 중)' : ''}
            </option>
          ))}
        </select>
      </div>
      <div className="btn-row">
        <button disabled={!pallet} onClick={() => outbound(false)}>출고</button>
        <button disabled={!pallet} className="urgent" onClick={() => outbound(true)}>긴급 출고</button>
      </div>
      <p className="hint block">진행 중인 팔레트로 다시 지시하면 WMS가 중복 작업지시로 차단합니다.</p>

      <h3>장애 주입</h3>
      <Toggle on={f.sensor} onClick={() => setFault('sensor')}
        title="센서 화물 미감지" desc="입고 작업 시 PLC가 에러코드 101을 올림 → ECS가 에러 처리 후 리셋" />
      <Toggle on={f.noResponse} onClick={() => setFault('noResponse')}
        title="PLC 무응답" desc="작업요청에 수신확인이 오지 않음 → 8초 후 ECS 타임아웃 처리" />
      <Toggle on={f.duplicateDone} onClick={() => setFault('duplicateDone')}
        title="완료 신호 중복" desc="같은 작업의 완료 신호가 한 번 더 들어옴 → ECS가 재고 이중 반영을 차단" />
    </div>
  )
}

function Queue({ queue, paused, run }) {
  if (queue.length === 0) {
    return <p className="hint block">대기 작업이 없습니다. {paused ? '' : '대기열을 편집하려면 ECS 작업 투입을 일시정지한 뒤 스케줄을 수신하세요.'}</p>
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>순번</th><th>서열</th><th>작업번호</th><th>유형</th><th>팔레트</th><th>품명</th>
            <th>LOT ID</th><th>위치</th><th>순서 · 취소</th>
          </tr>
        </thead>
        <tbody>
          {queue.map((o, i) => (
            <tr key={o.id} className={i === 0 ? 'next' : ''}>
              <td>{i === 0 ? <span className="badge RUNNING">다음</span> : i + 1}</td>
              <td>{o.seq}</td>
              <td><code>{o.orderNo}</code> {o.urgent && <span className="badge ERROR">긴급</span>}</td>
              <td>{o.type === 'INBOUND' ? '입고' : '출고'}</td>
              <td>{o.palletNo}</td>
              <td>{o.itemName}</td>
              <td><code>{o.lotId}</code></td>
              <td>{o.locationId}</td>
              <td className="actions">
                <button className="small ghost" disabled={i === 0} onClick={() => run(() => api(`/orders/${o.id}/move?dir=up`, { method: 'POST' }), null)}>▲</button>
                <button className="small ghost" disabled={i === queue.length - 1} onClick={() => run(() => api(`/orders/${o.id}/move?dir=down`, { method: 'POST' }), null)}>▼</button>
                <button className="small danger" onClick={() => run(() => api(`/orders/${o.id}`, { method: 'DELETE' }), '작업 취소')}>취소</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Toggle({ on, onClick, title, desc }) {
  return (
    <div className={`toggle ${on ? 'on' : ''}`} onClick={onClick} role="switch" aria-checked={on}>
      <span className="knob" />
      <div>
        <b>{title}</b>
        <small>{desc}</small>
      </div>
    </div>
  )
}

function Orders({ orders, run }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>서열</th><th>작업번호</th><th>유형</th><th>팔레트</th><th>품명</th>
            <th>LOT ID</th><th>위치</th><th>상태</th><th>메시지</th><th />
          </tr>
        </thead>
        <tbody>
          {orders.map(o => (
            <tr key={o.id}>
              <td>{o.seq}</td>
              <td><code>{o.orderNo}</code> {o.urgent && <span className="badge ERROR">긴급</span>}</td>
              <td>{o.type === 'INBOUND' ? '입고' : '출고'}</td>
              <td>{o.palletNo}</td>
              <td>{o.itemName}</td>
              <td><code>{o.lotId}</code></td>
              <td>{o.locationId}</td>
              <td><span className={`badge ${o.status}`}>{STATUS_LABEL[o.status]}</span></td>
              <td className="msg">{o.message}</td>
              <td>
                {o.status === 'ERROR' && (
                  <button className="small" onClick={() => run(() => api(`/orders/${o.id}/retry`, { method: 'POST' }), '재시도')}>
                    재시도
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Events({ events }) {
  return (
    <div className="events">
      {events.map(e => (
        <div key={e.id} className={`event ${e.level}`}>
          <time>{e.time?.slice(11, 19)}</time>
          <span className="src">{e.source}</span>
          <span>{e.message}</span>
        </div>
      ))}
    </div>
  )
}
