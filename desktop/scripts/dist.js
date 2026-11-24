// 설치 파일 빌드. 버전을 사람이 올리지 않아도 되게 git 커밋 수로 자동 결정한다.
//   package.json 의 version 은 "메이저.마이너" 기준으로만 쓰고(예: 0.1.0 → 0.1), 마지막 자리는 커밋 수다.
//   커밋할 때마다 올라가므로 실행 중인 앱이 새 빌드를 새 버전으로 인식해 자동 업데이트한다.
const { execFileSync, spawnSync } = require('node:child_process')
const path = require('node:path')
const pkg = require('../package.json')

const root = path.join(__dirname, '..')

function git(args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
}

function resolveVersion() {
  const [major, minor] = pkg.version.split('.')
  try {
    const count = Number(git(['rev-list', '--count', 'HEAD']))
    if (!Number.isInteger(count) || count < 1) throw new Error('커밋 수를 읽을 수 없습니다.')
    // 서버 검증(숫자 최대 4자리)과 맞춘다
    if (count > 9999) throw new Error('커밋 수가 9999 를 넘었습니다. 기준 버전(package.json)의 마이너를 올려 주세요.')
    return { version: `${major}.${minor}.${count}`, auto: true }
  } catch (e) {
    console.warn(`⚠️  git 으로 버전을 정하지 못해 package.json 의 ${pkg.version} 을 그대로 씁니다. (${e.message})`)
    return { version: pkg.version, auto: false }
  }
}

const { version, auto } = resolveVersion()

if (auto) {
  try {
    // 이 폴더(desktop)에 커밋하지 않은 변경이 있으면 같은 커밋 수로 다른 내용을 빌드하게 된다
    // → 이미 게시된 같은 버전과 구분되지 않아 실행 중인 앱이 업데이트를 받지 못한다.
    if (git(['status', '--porcelain', '--', '.']) !== '') {
      console.warn('⚠️  desktop 에 커밋하지 않은 변경이 있습니다. 커밋한 뒤 빌드해야 새 버전 번호가 붙어 앱이 업데이트를 받습니다.')
    }
  } catch {
    /* 확인할 수 없으면 넘어간다 */
  }
}

console.log(`\n▶ 설치 파일 빌드: 버전 ${version}\n`)

const cli = require.resolve('electron-builder/cli.js')
const result = spawnSync(
  process.execPath,
  [cli, '--win', '--x64', '--publish', 'never', `-c.extraMetadata.version=${version}`],
  { cwd: root, stdio: 'inherit' },
)
process.exit(result.status ?? 1)
