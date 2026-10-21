import { storeToRefs } from 'pinia'
import { useTransferStore } from '@/stores/transfers'

export function useTransfers() {
  const store = useTransferStore()
  store.initialize()
  const { transfers, transferHistoryWarning } = storeToRefs(store)
  return { transfers, transferHistoryWarning, enqueueTransfer: store.enqueueTransfer, delegateBrowserDownload: store.delegateBrowserDownload,
    clearFinishedTransfers: store.clearFinishedTransfers, assignTransferHost: store.assignTransferHost, transferSessionId: store.transferSessionId }
}
