/**
 * 파일 작업 정책
 * - 읽기·쓰기·삭제는 연결된 AddOns 폴더 안에서만 동작한다.
 * - 폴더 밖으로 나가는 경로, 심볼릭 링크·정션을 통한 우회, AddOns 폴더 자체 삭제를 거부한다.
 */
import assert from 'node:assert/strict'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { after, before, describe, it } from 'node:test'
import { FsOps } from '../src/fsOps'
import { FsError } from '../src/pathGuard'

let tmp: string
let addons: string
let outside: string
let ops: FsOps

const KEY = '1'
const bytes = (s: string) => new TextEncoder().encode(s)
const rejectsGuard = (p: Promise<unknown>) => assert.rejects(p, FsError)

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'addon-fs-'))
  addons = path.join(tmp, 'World of Warcraft', 'Interface', 'AddOns')
  outside = path.join(tmp, 'outside')
  fs.mkdirSync(addons, { recursive: true })
  fs.mkdirSync(outside)
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'secret')
  ops = new FsOps((key) => (key === KEY ? addons : null), { maxFileBytes: 1024 })
})

after(() => fs.rmSync(tmp, { recursive: true, force: true }))

describe('기본 동작', () => {
  it('쓰고, 읽고, 목록을 보고, 지운다', async () => {
    await ops.write(KEY, ['Foo', 'Foo.toc'], bytes('## Version: 1'))
    await ops.write(KEY, ['Foo', 'Libs', 'a.lua'], bytes('x'))
    assert.equal(new TextDecoder().decode(await ops.read(KEY, ['Foo', 'Foo.toc'])), '## Version: 1')
    assert.equal(await ops.stat(KEY, ['Foo']), 'directory')
    assert.equal(await ops.stat(KEY, ['Foo', 'Foo.toc']), 'file')
    assert.equal(await ops.stat(KEY, ['Nope']), null)
    const names = (await ops.list(KEY, ['Foo'])).map((e) => `${e.kind}:${e.name}`).sort()
    assert.deepEqual(names, ['directory:Libs', 'file:Foo.toc'])
    await ops.remove(KEY, ['Foo'], true)
    assert.equal(await ops.stat(KEY, ['Foo']), null)
  })

  it('연결되지 않은 키는 거부한다', async () => {
    await assert.rejects(ops.list('999', []), (e: FsError) => e.code === 'NotFound')
    assert.equal(await ops.exists('999'), false)
    assert.equal(await ops.exists(KEY), true)
  })
})

describe('폴더 밖 접근 차단', () => {
  it('.. 로 상위 폴더를 읽거나 쓸 수 없다', async () => {
    await rejectsGuard(ops.read(KEY, ['..', '..', '..', 'outside', 'secret.txt']))
    await rejectsGuard(ops.write(KEY, ['..', 'evil.txt'], bytes('x')))
    await rejectsGuard(ops.remove(KEY, ['..'], true))
    assert.equal(fs.existsSync(path.join(addons, '..', 'evil.txt')), false)
  })

  it('절대 경로·드라이브 경로를 조각으로 넘겨도 거부한다', async () => {
    await rejectsGuard(ops.read(KEY, [outside, 'secret.txt']))
    await rejectsGuard(ops.write(KEY, ['C:', 'evil.txt'], bytes('x')))
  })

  it('AddOns 폴더 자체는 삭제할 수 없다', async () => {
    await rejectsGuard(ops.remove(KEY, [], true))
    assert.ok(fs.existsSync(addons))
  })
})

describe('정션·심볼릭 링크 우회 차단', () => {
  const link = () => path.join(addons, 'Linked')

  before(() => {
    // 정션은 Windows 에서도 관리자 권한 없이 만들 수 있다. 다른 OS 에서는 일반 심볼릭 링크로 대신한다.
    fs.symlinkSync(outside, link(), process.platform === 'win32' ? 'junction' : 'dir')
  })

  it('링크 안의 파일을 읽을 수 없다', async () => {
    await rejectsGuard(ops.read(KEY, ['Linked', 'secret.txt']))
  })

  it('링크를 통해 폴더 밖에 쓸 수 없다', async () => {
    await rejectsGuard(ops.write(KEY, ['Linked', 'evil.txt'], bytes('x')))
    assert.equal(fs.existsSync(path.join(outside, 'evil.txt')), false)
  })

  it('링크를 통해 폴더 밖을 지울 수 없고 원본은 그대로다', async () => {
    await rejectsGuard(ops.remove(KEY, ['Linked', 'secret.txt'], true))
    await rejectsGuard(ops.remove(KEY, ['Linked'], true))
    assert.ok(fs.existsSync(path.join(outside, 'secret.txt')))
  })

  it('목록에는 링크가 나타나지 않는다', async () => {
    const names = (await ops.list(KEY, [])).map((e) => e.name)
    assert.ok(!names.includes('Linked'))
  })
})

describe('크기 제한과 잘못된 입력', () => {
  it('상한을 넘는 파일은 쓸 수 없다', async () => {
    await assert.rejects(ops.write(KEY, ['Big.bin'], new Uint8Array(2048)), (e: FsError) => e.code === 'TooLarge')
  })

  it('Uint8Array 가 아닌 데이터는 거부한다', async () => {
    await rejectsGuard(ops.write(KEY, ['a.txt'], 'text'))
    await rejectsGuard(ops.write(KEY, ['a.txt'], { length: 1 }))
  })

  it('같은 이름의 폴더 위에는 파일을 쓸 수 없다', async () => {
    await ops.mkdir(KEY, ['Dir'])
    await rejectsGuard(ops.write(KEY, ['Dir'], bytes('x')))
  })
})

describe('읽기 전용 폴더(자료실 원본)', () => {
  const LIB = 'library'
  let libRoot: string
  let libOps: FsOps

  before(() => {
    // 자료실 원본은 Interface\AddOns 형태가 아닌 임의의 폴더이고, 관리자가 읽기만 한다
    libRoot = path.join(tmp, 'my-library')
    fs.mkdirSync(path.join(libRoot, 'Details', 'Libs'), { recursive: true })
    fs.writeFileSync(path.join(libRoot, 'Details', 'Details.toc'), '## Version: 1\n')
    fs.writeFileSync(path.join(libRoot, 'Details', 'Libs', 'a.lua'), 'x')
    libOps = new FsOps((key) => (key === LIB ? libRoot : null), { isReadOnly: (key) => key === LIB })
  })

  it('목록·상태·읽기는 된다', async () => {
    assert.deepEqual((await libOps.list(LIB, [])).map((e) => e.name), ['Details'])
    assert.equal(await libOps.stat(LIB, ['Details']), 'directory')
    assert.equal(new TextDecoder().decode(await libOps.read(LIB, ['Details', 'Details.toc'])), '## Version: 1\n')
  })

  it('쓰기·폴더 만들기·삭제는 거부하고 원본은 그대로다', async () => {
    await rejectsGuard(libOps.write(LIB, ['Details', 'evil.lua'], bytes('x')))
    await rejectsGuard(libOps.mkdir(LIB, ['NewFolder']))
    await rejectsGuard(libOps.remove(LIB, ['Details'], true))
    await rejectsGuard(libOps.remove(LIB, ['Details', 'Details.toc'], false))
    assert.ok(fs.existsSync(path.join(libRoot, 'Details', 'Details.toc')))
    assert.equal(fs.existsSync(path.join(libRoot, 'Details', 'evil.lua')), false)
    assert.equal(fs.existsSync(path.join(libRoot, 'NewFolder')), false)
  })

  it('폴더 밖과 링크 우회 방어는 이 폴더에도 적용된다', async () => {
    await rejectsGuard(libOps.read(LIB, ['..', 'outside', 'secret.txt']))
    const link = path.join(libRoot, 'Linked')
    fs.symlinkSync(outside, link, process.platform === 'win32' ? 'junction' : 'dir')
    await rejectsGuard(libOps.read(LIB, ['Linked', 'secret.txt']))
    assert.ok(!(await libOps.list(LIB, [])).some((e) => e.name === 'Linked'))
  })

  it('같은 FsOps 라도 읽기 전용이 아닌 키는 쓸 수 있다', async () => {
    const mixed = new FsOps((key) => (key === LIB ? libRoot : key === KEY ? addons : null), { isReadOnly: (key) => key === LIB })
    await mixed.write(KEY, ['Writable', 'a.lua'], bytes('ok'))
    assert.equal(fs.readFileSync(path.join(addons, 'Writable', 'a.lua'), 'utf8'), 'ok')
    await mixed.remove(KEY, ['Writable'], true)
  })
})
