/**
 * 외부로 여는 주소 허용 정책
 * - https 와 CurseForge 앱 설치 주소(curseforge://install?addonId=숫자&fileId=숫자)만 허용한다.
 * - 그 밖의 프로토콜(file:, javascript:, 다른 사용자 지정 프로토콜)과 형식을 벗어난 curseforge 주소는 거부한다.
 */
import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { isAllowedExternalUrl } from '../src/externalUrl'

describe('isAllowedExternalUrl', () => {
  it('https 주소를 허용한다', () => {
    assert.ok(isAllowedExternalUrl('https://www.curseforge.com/wow/addons/questie'))
    assert.ok(isAllowedExternalUrl('https://github.com/x/y'))
  })

  it('http 와 그 밖의 프로토콜은 거부한다', () => {
    for (const u of ['http://example.com', 'file:///C:/Windows/System32/cmd.exe', 'javascript:alert(1)',
      'data:text/html,<script>1</script>', 'ms-msdt:/id', 'steam://run/1', 'vscode://file/x', 'ftp://x.example/a']) {
      assert.equal(isAllowedExternalUrl(u), false, u)
    }
  })

  it('CurseForge 앱 설치 주소를 허용한다', () => {
    assert.ok(isAllowedExternalUrl('curseforge://install?addonId=334372'))
    assert.ok(isAllowedExternalUrl('curseforge://install?addonId=334372&fileId=900'))
    assert.ok(isAllowedExternalUrl('curseforge://install/?addonId=1&fileId=2'))
  })

  it('형식을 벗어난 curseforge 주소는 거부한다', () => {
    for (const u of [
      'curseforge://install',                              // 매개변수 없음
      'curseforge://install?fileId=1',                     // addonId 없음
      'curseforge://install?addonId=abc',                  // 숫자가 아님
      'curseforge://install?addonId=1;calc',               // 숫자 뒤에 다른 것
      'curseforge://install?addonId=1&fileId=-1',          // 음수
      'curseforge://install?addonId=1&evil=1',             // 허용하지 않은 매개변수
      'curseforge://install?addonId=1&addonId=2',          // 같은 매개변수 반복
      'curseforge://other?addonId=1',                      // install 이 아님
      'curseforge://install/extra?addonId=1',              // 경로가 붙음
      'curseforge://user:pw@install?addonId=1',            // 계정 정보
      'curseforge://install?addonId=1#frag',               // 조각
      'curseforge:install?addonId=1',                      // 슬래시가 없는 형태
    ]) {
      assert.equal(isAllowedExternalUrl(u), false, u)
    }
  })

  it('읽을 수 없는 값은 거부한다', () => {
    assert.equal(isAllowedExternalUrl(''), false)
    assert.equal(isAllowedExternalUrl('not a url'), false)
  })
})
