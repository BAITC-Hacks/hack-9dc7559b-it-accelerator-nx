import { useContext, useSyncExternalStore } from 'react';
import { CommerceContext } from '../components/cart/context';

export function useCommerce() {
  const driver = useContext(CommerceContext);
  const view = useSyncExternalStore(driver.subscribe, driver.getSnapshot, driver.getSnapshot);
  return { driver, view };
}
