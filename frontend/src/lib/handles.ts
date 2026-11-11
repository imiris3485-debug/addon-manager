// AddOns 폴더 핸들(File System Access API)을 환경별로 IndexedDB 에 보관한다.
// 핸들은 이 브라우저에만 존재한다. 다른 PC/브라우저에서는 환경마다 폴더를 다시 선택해야 한다.

const DB_NAME = 'addon-manager'
const STORE = 'handles'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function withStore<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb()
  return new Promise<T>((resolve, reject) => {
    const req = fn(db.transaction(STORE, mode).objectStore(STORE))
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

export const isFolderAccessSupported = () => 'showDirectoryPicker' in window

export async function saveHandle(environmentId: number, handle: FileSystemDirectoryHandle) {
  await withStore('readwrite', (s) => s.put(handle, environmentId))
}

export async function loadHandle(environmentId: number): Promise<FileSystemDirectoryHandle | undefined> {
  return withStore<FileSystemDirectoryHandle | undefined>('readonly', (s) => s.get(environmentId))
}

export async function deleteHandle(environmentId: number) {
  await withStore('readwrite', (s) => s.delete(environmentId))
}

// 표준 lib.dom 에 없는 권한 API
interface PermissionedHandle {
  queryPermission(opts: { mode: 'readwrite' }): Promise<PermissionState>
  requestPermission(opts: { mode: 'readwrite' }): Promise<PermissionState>
}

/** 권한 창을 띄우지 않고 이미 허용된 상태인지만 확인한다(자동 스캔 여부 판단용). */
export async function hasPermission(handle: FileSystemDirectoryHandle): Promise<boolean> {
  const h = handle as unknown as PermissionedHandle
  return (await h.queryPermission({ mode: 'readwrite' })) === 'granted'
}

/** 읽기/쓰기 권한을 확인하고 없으면 요청한다. 사용자 클릭 이벤트 안에서 호출해야 한다. */
export async function ensurePermission(handle: FileSystemDirectoryHandle): Promise<boolean> {
  const h = handle as unknown as PermissionedHandle
  const opts = { mode: 'readwrite' } as const
  if ((await h.queryPermission(opts)) === 'granted') return true
  return (await h.requestPermission(opts)) === 'granted'
}

export async function pickAddonsFolder(): Promise<FileSystemDirectoryHandle> {
  const picker = (window as unknown as {
    showDirectoryPicker: (o: object) => Promise<FileSystemDirectoryHandle>
  }).showDirectoryPicker
  return picker.call(window, { id: 'wow-addons', mode: 'readwrite' })
}
