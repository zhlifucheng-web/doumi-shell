import { createPinia, getActivePinia, setActivePinia, storeToRefs } from 'pinia'
import { useTransferStore } from '../stores/transfers.js'

function getStore() {
  if (!getActivePinia()) {
    setActivePinia(createPinia())
  }
  const store = useTransferStore()
  store.initialize()
  return store
}

export function useTransfers() {
  const store = getStore()
  const { transfers, transferHistoryWarning } = storeToRefs(store)
  return {
    transfers,
    transferHistoryWarning,
    enqueueTransfer: store.enqueueTransfer,
    delegateBrowserDownload: store.delegateBrowserDownload,
    clearFinishedTransfers: store.clearFinishedTransfers,
    assignTransferHost: store.assignTransferHost,
    transferSessionId: store.transferSessionId
  }
}

export const enqueueTransfer = (...args) => getStore().enqueueTransfer(...args)
export const delegateBrowserDownload = (...args) => getStore().delegateBrowserDownload(...args)
export const clearFinishedTransfers = (...args) => getStore().clearFinishedTransfers(...args)
export const assignTransferHost = (...args) => getStore().assignTransferHost(...args)
export const transferSessionId = (...args) => getStore().transferSessionId(...args)
export const transfers = new Proxy([], {
  get(target, prop, receiver) {
    return Reflect.get(getStore().transfers, prop, receiver)
  },
  set(target, prop, value, receiver) {
    return Reflect.set(getStore().transfers, prop, value, receiver)
  }
})
