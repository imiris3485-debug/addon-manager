import type { LibraryAddon } from './types'

/**
 * 같은 묶음에 딸린 모듈 폴더.
 * CurseForge 같은 곳에서 받은 애드온은 한 묶음이 폴더 여러 개로 오는 경우가 많다(예: Details 와 Details_Compare2, Details_DataStorage …,
 * AtlasLootClassic 과 AtlasLootClassic_Options, AtlasLootClassic_PvP …). 자료실은 폴더 하나가 항목 하나라서 그대로 두면 목록이 모듈로 가득 찬다.
 * 이름이 `부모_` 로 시작하고 자료실에 부모가 있으면 그 부모의 모듈로 본다(모듈 쪽이 부모를 필요로 하는 방향이라 .toc 의존성으로는 묶이지 않는다).
 */
export function withModules(list: Omit<LibraryAddon, 'modules'>[]): LibraryAddon[] {
  const names = list.map((a) => a.name)
  return list.map((a) => {
    const prefix = a.name.toLowerCase() + '_'
    return { ...a, modules: names.filter((n) => n.toLowerCase().startsWith(prefix)) }
  })
}

/** 이 애드온을 설치·갱신할 때 함께 설치·갱신할 애드온 이름: 필요한 애드온 + 딸린 모듈 */
export const needs = (a: LibraryAddon): string[] => [...a.requires, ...a.modules]

export const lc = (s: string) => s.toLowerCase()

/** 함께 설치되는 자료실 애드온(의존성의 의존성까지, 자기 자신 제외). 순환해도 한 번씩만 담는다. */
export function transitiveDeps(item: LibraryAddon, byName: Map<string, LibraryAddon>): LibraryAddon[] {
  const out: LibraryAddon[] = []
  const seen = new Set([lc(item.name)])
  const queue = [item]
  for (let depth = 0; queue.length > 0 && depth < 10; depth++) {
    const next: LibraryAddon[] = []
    for (const a of queue) {
      for (const r of needs(a)) {
        const dep = byName.get(lc(r))
        if (dep && !seen.has(lc(dep.name))) {
          seen.add(lc(dep.name))
          out.push(dep)
          next.push(dep)
        }
      }
    }
    queue.length = 0
    queue.push(...next)
  }
  return out
}

/**
 * 다른 애드온이 필요로 하는 애드온(소문자 이름). 목록에서는 부모 아래로 묶어 숨긴다(예: QuestieDB 는 Questie 아래).
 * 서로 필요로 하는 순환 관계는 어느 쪽도 숨기지 않는다.
 */
export function dependencyNames(library: LibraryAddon[]): Set<string> {
  const byName = new Map(library.map((a) => [lc(a.name), a]))
  const hidden = new Set<string>()
  for (const a of library) {
    for (const r of needs(a)) {
      const dep = byName.get(lc(r))
      if (!dep || dep === a) continue
      // 서로 필요로 하는 순환 관계에서는 모듈(이름이 `부모_` 로 시작)만 숨기고 그 밖에는 어느 쪽도 숨기지 않는다
      if (needs(dep).some((x) => lc(x) === lc(a.name)) && !lc(dep.name).startsWith(lc(a.name) + '_')) continue
      hidden.add(lc(dep.name))
    }
  }
  return hidden
}

