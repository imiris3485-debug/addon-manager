/**
 * 경로 검증 정책
 * - 경로는 이름 조각 배열로만 받고, 폴더 밖으로 나가거나 Windows 에서 다른 의미를 갖는 이름은 거부한다.
 * - 일반적인 애드온 경로(한글 포함)는 통과시킨다.
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { FsError, resolveInside, validateSegments } from '../src/pathGuard'

const rejects = (segs: unknown) => assert.throws(() => validateSegments(segs), FsError)

describe('validateSegments', () => {
  it('일반 애드온 경로는 통과한다', () => {
    assert.deepEqual(validateSegments(['Details', 'Libs', 'LibStub', 'LibStub.lua']), ['Details', 'Libs', 'LibStub', 'LibStub.lua'])
    assert.deepEqual(validateSegments(['한글애드온', '.hidden', 'a b.toc']), ['한글애드온', '.hidden', 'a b.toc'])
    assert.deepEqual(validateSegments([]), [])
  })

  it('상위 폴더 이동(..)과 현재 폴더(.)를 거부한다', () => {
    rejects(['..'])
    rejects(['a', '..', 'b'])
    rejects(['.'])
  })

  it('구분자·드라이브·대체 스트림이 들어간 이름을 거부한다', () => {
    rejects(['a/b'])
    rejects(['a\\b'])
    rejects(['..\\..\\Windows'])
    rejects(['C:'])
    rejects(['file.txt:stream'])
    rejects(['/etc'])
  })

  it('Windows 장치 이름과 끝의 점·공백을 거부한다', () => {
    rejects(['con'])
    rejects(['NUL.txt'])
    rejects(['com1'])
    rejects(['name.'])
    rejects(['name '])
  })

  it('빈 이름, 문자열이 아닌 값, 너무 긴 이름과 깊은 경로를 거부한다', () => {
    rejects([''])
    rejects([1])
    rejects([null])
    rejects('a/b')
    rejects(undefined)
    rejects(['a'.repeat(256)])
    rejects(Array.from({ length: 33 }, () => 'a'))
  })

  it('제어문자와 NUL 을 거부한다', () => {
    rejects(['a\u0000b'])
    rejects(['a\nb'])
  })
})

describe('resolveInside', () => {
  it('기준 폴더 안의 경로를 돌려준다', () => {
    const root = process.platform === 'win32' ? 'C:\\base\\AddOns' : '/base/AddOns'
    assert.ok(resolveInside(root, ['a', 'b']).startsWith(root))
  })
})
