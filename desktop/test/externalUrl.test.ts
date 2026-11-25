/**
 * 외부로 여는 주소 허용 정책
 * - https 주소만 허용한다.
 * - 그 밖의 프로토콜(http, file:, javascript:, 프로그램을 실행하는 사용자 지정 프로토콜)은 거부한다.
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { isAllowedExternalUrl } from '../src/externalUrl'

describe('isAllowedExternalUrl', () => {
  it('https 주소를 허용한다', () => {
    assert.ok(isAllowedExternalUrl('https://github.com/x/y'))
    assert.ok(isAllowedExternalUrl('https://addon-manager.shapinel.com/download'))
  })

  it('http 와 그 밖의 프로토콜은 거부한다', () => {
    for (const u of ['http://example.com', 'file:///C:/Windows/System32/cmd.exe', 'javascript:alert(1)',
      'data:text/html,<script>1</script>', 'ms-msdt:/id', 'steam://run/1', 'vscode://file/x', 'ftp://x.example/a',
      'curseforge://install?addonId=1']) {
      assert.equal(isAllowedExternalUrl(u), false, u)
    }
  })

  it('읽을 수 없는 값은 거부한다', () => {
    assert.equal(isAllowedExternalUrl(''), false)
    assert.equal(isAllowedExternalUrl('not a url'), false)
    assert.equal(isAllowedExternalUrl('https://'), false)
  })
})
