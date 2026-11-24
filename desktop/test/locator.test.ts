/**
 * AddOns 폴더 탐색·연결 정책
 * - 연결 대상은 …\Interface\AddOns 형태의 폴더뿐이다.
 * - 게임 폴더나 _forever_ 같은 클라이언트 폴더를 골라도 안쪽의 AddOns 를 찾는다.
 * - 화면이 만든 임의의 경로나 키로는 폴더를 연결할 수 없다.
 */
import assert from 'node:assert/strict'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { after, before, describe, it } from 'node:test'
import { findForeverAddons, isAddonsPath, isForeverAddonsPath, resolveAddonsPaths } from '../src/locator'
import { RootStore } from '../src/rootStore'

let tmp: string
const mk = (...parts: string[]) => {
  const p = path.join(tmp, ...parts)
  fs.mkdirSync(p, { recursive: true })
  return p
}

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'addon-loc-'))
})
after(() => fs.rmSync(tmp, { recursive: true, force: true }))

describe('isAddonsPath', () => {
  it('Interface\\AddOns 형태만 통과한다', () => {
    assert.ok(isAddonsPath(path.join('x', 'Interface', 'AddOns')))
    assert.ok(isAddonsPath(path.join('x', 'interface', 'addons')))
    assert.ok(!isAddonsPath(path.join('x', 'AddOns')))
    assert.ok(!isAddonsPath(path.join('x', 'Interface')))
    assert.ok(!isAddonsPath(path.join('C:', 'Windows')))
  })
})

describe('resolveAddonsPaths', () => {
  it('AddOns 폴더를 직접 고르면 그대로 쓴다', async () => {
    const addons = mk('a', 'Interface', 'AddOns')
    assert.deepEqual(await resolveAddonsPaths(addons), [addons])
  })

  it('게임 폴더나 Interface 폴더를 고르면 안쪽 AddOns 를 찾는다', async () => {
    const game = mk('b')
    const addons = mk('b', 'Interface', 'AddOns')
    assert.deepEqual(await resolveAddonsPaths(game), [addons])
    assert.deepEqual(await resolveAddonsPaths(path.join(game, 'Interface')), [addons])
  })

  it('_forever_ 같은 클라이언트 폴더를 각각 찾는다', async () => {
    const game = mk('c')
    const forever = mk('c', '_forever_', 'Interface', 'AddOns')
    assert.deepEqual(await resolveAddonsPaths(game), [forever])
    assert.deepEqual(await resolveAddonsPaths(path.join(game, '_forever_')), [forever])
    const retail = mk('c', '_retail_', 'Interface', 'AddOns')
    assert.deepEqual((await resolveAddonsPaths(game)).sort(), [forever, retail].sort())
  })

  it('관계없는 폴더나 없는 폴더는 빈 목록이다', async () => {
    assert.deepEqual(await resolveAddonsPaths(mk('d', 'Documents')), [])
    assert.deepEqual(await resolveAddonsPaths(path.join(tmp, 'missing')), [])
  })
})

describe('와우 포에버(_forever_) 자동 검색', () => {
  it('게임 폴더나 _forever_ 폴더에서 포에버의 AddOns 만 찾는다', async () => {
    const game = mk('h')
    const forever = mk('h', '_forever_', 'Interface', 'AddOns')
    mk('h', '_retail_', 'Interface', 'AddOns')
    mk('h', '_classic_', 'Interface', 'AddOns')
    assert.equal(await findForeverAddons(game), forever)
    assert.equal(await findForeverAddons(path.join(game, '_forever_')), forever)
  })

  it('포에버 폴더가 없으면 다른 클라이언트가 있어도 null 이다', async () => {
    const game = mk('i')
    mk('i', '_retail_', 'Interface', 'AddOns')
    mk('i', 'Interface', 'AddOns')
    assert.equal(await findForeverAddons(game), null)
    assert.equal(await findForeverAddons(path.join(tmp, 'missing')), null)
  })

  it('폴더 이름의 대소문자는 구분하지 않는다', async () => {
    const game = mk('j')
    mk('j', '_FOREVER_', 'Interface', 'AddOns')
    assert.ok(await findForeverAddons(game))
  })

  it('경로에 _forever_ 가 있는지 판별한다', () => {
    assert.ok(isForeverAddonsPath(path.join('C:', 'WoW', '_forever_', 'Interface', 'AddOns')))
    assert.ok(!isForeverAddonsPath(path.join('C:', 'WoW', '_retail_', 'Interface', 'AddOns')))
  })
})

describe('RootStore', () => {
  it('AddOns 가 아닌 경로는 대기 목록에 넣을 수 없다', () => {
    const store = new RootStore(path.join(tmp, 'r1.json'))
    assert.throws(() => store.addPending(path.join(tmp, 'Documents')))
  })

  it('대기 키를 환경에 묶으면 저장되고 다시 읽힌다', () => {
    const file = path.join(tmp, 'r2.json')
    const addons = mk('e', 'Interface', 'AddOns')
    const store = new RootStore(file)
    const key = store.addPending(addons)
    assert.equal(store.rootOf(key), addons)
    store.bind(key, 7)
    assert.equal(store.rootOf('7'), addons)
    assert.equal(store.rootOf(key), null)
    assert.equal(new RootStore(file).rootOf('7'), addons)
  })

  it('만들어 낸 키나 잘못된 환경 번호로는 묶을 수 없다', () => {
    const store = new RootStore(path.join(tmp, 'r3.json'))
    assert.throws(() => store.bind('p-00000000-0000-0000-0000-000000000000', 1))
    assert.throws(() => store.bind('C:\\Windows', 1))
    const key = store.addPending(mk('f', 'Interface', 'AddOns'))
    assert.throws(() => store.bind(key, -1))
    assert.throws(() => store.bind(key, '1'))
    assert.equal(store.rootOf('C:\\Windows'), null)
  })

  it('저장 파일이 변조되어도 AddOns 가 아닌 경로는 무시한다', () => {
    const file = path.join(tmp, 'r4.json')
    fs.writeFileSync(file, JSON.stringify({ '1': 'C:\\Windows\\System32', '2': mk('g', 'Interface', 'AddOns') }))
    const store = new RootStore(file)
    assert.equal(store.rootOf('1'), null)
    assert.ok(store.rootOf('2'))
  })
})
