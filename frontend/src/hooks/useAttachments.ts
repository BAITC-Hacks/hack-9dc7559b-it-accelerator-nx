import { useContext, useSyncExternalStore } from 'react';
import { AttachmentContext } from '../components/attachments/context';

export function useAttachments() {
  const driver = useContext(AttachmentContext);
  const view = useSyncExternalStore(driver.subscribe, driver.getSnapshot, driver.getSnapshot);
  return { driver, view };
}
